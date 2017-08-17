package com.microsoft.jenkins.acs.commands;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.google.common.annotations.VisibleForTesting;
import com.jcraft.jsch.JSchException;
import com.microsoft.jenkins.acs.Messages;
import com.microsoft.jenkins.acs.orchestrators.DeploymentConfig;
import com.microsoft.jenkins.acs.util.Constants;
import com.microsoft.jenkins.acs.util.DeployHelper;
import com.microsoft.jenkins.azurecommons.JobContext;
import com.microsoft.jenkins.azurecommons.command.CommandState;
import com.microsoft.jenkins.azurecommons.command.IBaseCommandData;
import com.microsoft.jenkins.azurecommons.command.ICommand;
import com.microsoft.jenkins.azurecommons.remote.SSHClient;
import hudson.FilePath;
import hudson.model.Item;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.docker.commons.credentials.DockerRegistryEndpoint;
import org.jenkinsci.plugins.docker.commons.credentials.DockerRegistryToken;

import java.io.IOException;
import java.util.List;

import static com.microsoft.jenkins.acs.util.DeployHelper.escapeSingleQuote;

public class SwarmDeploymentCommand implements ICommand<SwarmDeploymentCommand.ISwarmDeploymentCommandData> {
    private final ExternalUtils externalUtils;

    public SwarmDeploymentCommand() {
        this(ExternalUtils.DEFAULT);
    }

    @VisibleForTesting
    SwarmDeploymentCommand(ExternalUtils externalUtils) {
        this.externalUtils = externalUtils;
    }

    public void execute(SwarmDeploymentCommand.ISwarmDeploymentCommandData context) {
        final String host = context.getMgmtFQDN();
        final SSHUserPrivateKey sshCredentials = context.getSshCredentials();
        final JobContext jobContext = context.getJobContext();

        try {
            final DeploymentConfig config = context.getDeploymentConfig();
            if (config == null) {
                context.logError(Messages.DeploymentConfig_invalidConfig());
                return;
            }

            SSHClient client = externalUtils.buildSSHClient(host, Constants.SWARM_SSH_PORT, sshCredentials)
                    .withLogger(jobContext.logger());

            try (SSHClient connected = client.connect()) {
                prepareCredendtialsOnAgents(context, jobContext, connected);

                final FilePath[] configFiles = config.getConfigFiles();
                for (FilePath configFile : configFiles) {
                    final String deployedFilename = externalUtils.buildRemoteDeployConfigName();
                    context.logStatus(Messages.SwarmDeploymentCommand_copyConfigFileTo(
                            configFile.getRemote(), connected.getHost(), deployedFilename));

                    connected.copyTo(
                            jobContext.replaceMacro(configFile.read(), context.isEnableConfigSubstitution()),
                            deployedFilename);

                    final String escapedName = escapeSingleQuote(deployedFilename);

                    if (context.isSwarmRemoveContainersFirst()) {
                        context.logStatus(Messages.SwarmDeploymentCommand_removingDockerContainers());
                        try {
                            connected.execRemote(String.format("DOCKER_HOST=:2375 docker-compose -f '%s' down",
                                    escapedName));
                        } catch (SSHClient.ExitStatusException ex) {
                            // the service was not found
                            context.logStatus(ex.getMessage());
                        }
                    }

                    // Note that we have to specify DOCKER_HOST in the command rather than using `ChannelExec.setEnv`
                    // as the latter one sets environment variable through SSH protocol but the default sshd_config
                    // doesn't allow this
                    context.logStatus(Messages.SwarmDeploymentCommand_updatingDockerContainers());
                    connected.execRemote(String.format("DOCKER_HOST=:2375 docker-compose -f '%s' up -d",
                            escapedName));

                    context.logStatus(Messages.SwarmDeploymentCommand_removeTempFile(deployedFilename));
                    connected.execRemote("rm -f -- '" + escapedName + "'");
                }
            }

            context.setCommandState(CommandState.Success);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            context.logError(e);
        } catch (Exception e) {
            context.logError(e);
        }
    }

    private void prepareCredendtialsOnAgents(
            SwarmDeploymentCommand.ISwarmDeploymentCommandData context,
            JobContext jobContext,
            SSHClient client) throws Exception {
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
            client.execRemote(command, false, false);
        }
    }

    @VisibleForTesting
    interface ExternalUtils {
        SSHClient buildSSHClient(String host,
                                 int port,
                                 SSHUserPrivateKey credentials) throws JSchException;

        String buildRemoteDeployConfigName();

        ExternalUtils DEFAULT = new ExternalUtils() {
            @Override
            public SSHClient buildSSHClient(String host, int port, SSHUserPrivateKey credentials) throws JSchException {
                return new SSHClient(host, port, credentials);
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
