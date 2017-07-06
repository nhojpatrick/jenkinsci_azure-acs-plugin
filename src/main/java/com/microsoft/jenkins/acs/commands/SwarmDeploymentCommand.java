package com.microsoft.jenkins.acs.commands;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.jcraft.jsch.JSchException;
import com.microsoft.jenkins.acs.JobContext;
import com.microsoft.jenkins.acs.Messages;
import com.microsoft.jenkins.acs.orchestrators.DeploymentConfig;
import com.microsoft.jenkins.acs.util.Constants;
import com.microsoft.jenkins.acs.util.DeployHelper;
import com.microsoft.jenkins.acs.util.JSchClient;
import hudson.FilePath;

import java.io.IOException;

public class SwarmDeploymentCommand implements ICommand<SwarmDeploymentCommand.ISwarmDeploymentCommandData> {
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

            client = new JSchClient(host, Constants.SWARM_SSH_PORT, linuxAdminUsername, sshCredentials, context);

            final FilePath[] configFiles = config.getConfigFiles();
            for (final FilePath configFile : configFiles) {
                final String deployedFilename = DeployHelper.generateRandomDeploymentFileName("yml");
                context.logStatus(Messages.SwarmDeploymentCommand_copyConfigFileTo(
                        configFile.getRemote(), client.getHost(), deployedFilename));

                client.copyTo(
                        jobContext.replaceMacro(configFile.read(), context.isEnableConfigSubstitution()),
                        deployedFilename);

                if (context.getSwarmRemoveContainersFirst()) {
                    context.logStatus(Messages.SwarmDeploymentCommand_removingDockerContainers());
                    client.execRemote(String.format("DOCKER_HOST=:2375 docker-compose -f %s down", deployedFilename));
                }

                // Note that we have to specify DOCKER_HOST in the command rather than using `ChannelExec.setEnv`
                // as the latter one sets environment variable through SSH protocol but the default sshd_config
                // doesn't allow this
                context.logStatus(Messages.SwarmDeploymentCommand_updatingDockerContainers());
                client.execRemote(String.format("DOCKER_HOST=:2375 docker-compose -f %s up -d", deployedFilename));

                context.logStatus(Messages.SwarmDeploymentCommand_removeTempFile(deployedFilename));
                client.execRemote("rm -f " + deployedFilename);
            }

            context.setDeploymentState(DeploymentState.Success);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            context.logError(e);
        } catch (JSchException | IOException e) {
            context.logError(e);
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

    public interface ISwarmDeploymentCommandData extends IBaseCommandData {
        String getMgmtFQDN();

        String getLinuxAdminUsername();

        SSHUserPrivateKey getSshCredentials();

        DeploymentConfig getDeploymentConfig() throws IOException, InterruptedException;

        boolean isEnableConfigSubstitution();

        boolean getSwarmRemoveContainersFirst();
    }
}
