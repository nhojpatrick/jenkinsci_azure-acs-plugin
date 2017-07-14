package com.microsoft.jenkins.acs.commands;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.google.common.annotations.VisibleForTesting;
import com.jcraft.jsch.JSchException;
import com.microsoft.jenkins.acs.JobContext;
import com.microsoft.jenkins.acs.Messages;
import com.microsoft.jenkins.acs.orchestrators.DeploymentConfig;
import com.microsoft.jenkins.acs.util.Constants;
import com.microsoft.jenkins.acs.util.DeployHelper;
import com.microsoft.jenkins.acs.util.JSchClient;
import hudson.FilePath;
import hudson.model.Item;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.docker.commons.credentials.DockerRegistryEndpoint;
import org.jenkinsci.plugins.docker.commons.credentials.DockerRegistryToken;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.List;

import static com.microsoft.jenkins.acs.util.DeployHelper.escapeSingleQuote;

public class SwarmDeploymentCommand implements ICommand<SwarmDeploymentCommand.ISwarmDeploymentCommandData> {
    private final ExternalUtils externalUtils;

    public SwarmDeploymentCommand() {
        this(ExternalUtils.DEFAULT);
    }

    @VisibleForTesting
    SwarmDeploymentCommand(final ExternalUtils externalUtils) {
        this.externalUtils = externalUtils;
    }

    public void execute(final SwarmDeploymentCommand.ISwarmDeploymentCommandData context) {
        final String host = context.getMgmtFQDN();
        final SSHUserPrivateKey sshCredentials = context.getSshCredentials();
        final String linuxAdminUsername = context.getLinuxAdminUsername();
        final JobContext jobContext = context.jobContext();

        JSchClient client = null;
        try {
            final DeploymentConfig config = context.getDeploymentConfig();
            if (config == null) {
                context.logError(Messages.DeploymentConfig_invalidConfig());
                return;
            }

            client = externalUtils.buildJSchClient(
                    host, Constants.SWARM_SSH_PORT, linuxAdminUsername, sshCredentials, context);

            prepareCredendtialsOnAgents(context, jobContext, client);

            final FilePath[] configFiles = config.getConfigFiles();
            for (final FilePath configFile : configFiles) {
                final String deployedFilename = externalUtils.buildRemoteDeployConfigName();
                context.logStatus(Messages.SwarmDeploymentCommand_copyConfigFileTo(
                        configFile.getRemote(), client.getHost(), deployedFilename));

                client.copyTo(
                        jobContext.replaceMacro(configFile.read(), context.isEnableConfigSubstitution()),
                        deployedFilename);

                final String escapedName = escapeSingleQuote(deployedFilename);

                if (context.isSwarmRemoveContainersFirst()) {
                    context.logStatus(Messages.SwarmDeploymentCommand_removingDockerContainers());
                    client.execRemote(String.format("DOCKER_HOST=:2375 docker-compose -f '%s' down",
                            escapedName));
                }

                // Note that we have to specify DOCKER_HOST in the command rather than using `ChannelExec.setEnv`
                // as the latter one sets environment variable through SSH protocol but the default sshd_config
                // doesn't allow this
                context.logStatus(Messages.SwarmDeploymentCommand_updatingDockerContainers());
                client.execRemote(String.format("DOCKER_HOST=:2375 docker-compose -f '%s' up -d",
                        escapedName));

                context.logStatus(Messages.SwarmDeploymentCommand_removeTempFile(deployedFilename));
                client.execRemote("rm -f -- '" + escapedName + "'");
            }

            context.setDeploymentState(DeploymentState.Success);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            context.logError(e);
        } catch (JSchException | IOException | IllegalArgumentException e) {
            context.logError(e);
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

    private void prepareCredendtialsOnAgents(
            final SwarmDeploymentCommand.ISwarmDeploymentCommandData context,
            final JobContext jobContext,
            final JSchClient client) throws IOException, InterruptedException, JSchException {
        final List<DockerRegistryEndpoint> containerRegistryCredentials = context.getContainerRegistryCredentials();
        final Item tokenContext = jobContext.getRun().getParent();

        for (DockerRegistryEndpoint endpoint : containerRegistryCredentials) {
            DockerRegistryToken token = endpoint.getToken(tokenContext);
            if (token == null) {
                // no credentials filled for this entry
                continue;
            }

            String auth = StringUtils.trimToEmpty(token.getToken());
            if (StringUtils.isEmpty(auth)) {
                throw new IllegalArgumentException(Messages.SwarmDeploymentConfig_noAuthTokenFor(endpoint));
            }

            String decoded = new String(Base64.decodeBase64(auth), Constants.DEFAULT_CHARSET);
            String[] parts = decoded.split(":", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException(Messages.SwarmDeploymentConfig_malformedAuthTokenFor(endpoint));
            }

            String username = parts[0];
            String password = parts[1];
            String server = endpoint.getEffectiveUrl().toString();

            final String command = String.format("docker login -u '%s' -p '%s' '%s'",
                    escapeSingleQuote(username), escapeSingleQuote(password), escapeSingleQuote(server));

            context.logStatus(Messages.SwarmDeploymentConfig_addCredentialsFor(server));
            client.execRemote(command, false);
        }
    }

    @VisibleForTesting
    interface ExternalUtils {
        JSchClient buildJSchClient(String host,
                                   int port,
                                   @Nullable String username,
                                   SSHUserPrivateKey credentials,
                                   @Nullable IBaseCommandData context);

        String buildRemoteDeployConfigName();

        ExternalUtils DEFAULT = new ExternalUtils() {
            @Override
            public JSchClient buildJSchClient(
                    final String host,
                    final int port,
                    @Nullable final String username,
                    final SSHUserPrivateKey credentials,
                    @Nullable final IBaseCommandData context) {
                return new JSchClient(host, port, username, credentials, context);
            }

            @Override
            public String buildRemoteDeployConfigName() {
                return DeployHelper.generateRandomDeploymentFileName("yml");
            }
        };
    }

    public interface ISwarmDeploymentCommandData extends IBaseCommandData {
        String getMgmtFQDN();

        String getLinuxAdminUsername();

        SSHUserPrivateKey getSshCredentials();

        DeploymentConfig getDeploymentConfig() throws IOException, InterruptedException;

        boolean isEnableConfigSubstitution();

        boolean isSwarmRemoveContainersFirst();

        List<DockerRegistryEndpoint> getContainerRegistryCredentials();
    }
}
