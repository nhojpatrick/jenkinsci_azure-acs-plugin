/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */
package com.microsoft.jenkins.acs.commands;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.jcraft.jsch.JSchException;
import com.microsoft.jenkins.acs.JobContext;
import com.microsoft.jenkins.acs.Messages;
import com.microsoft.jenkins.acs.util.Constants;
import com.microsoft.jenkins.acs.util.JSchClient;
import com.microsoft.jenkins.acs.util.JsonHelper;
import hudson.FilePath;

import java.io.IOException;
import java.util.Calendar;

public class MarathonDeploymentCommand implements ICommand<MarathonDeploymentCommand.IMarathonDeploymentCommandData> {
    @Override
    public void execute(final IMarathonDeploymentCommandData context) {
        final String host = context.getMgmtFQDN();
        final SSHUserPrivateKey sshCredentials = context.getSshCredentials();
        final String linuxAdminUsername = context.getLinuxAdminUsername();
        final String relativeFilePath = context.getConfigFilePaths();
        final JobContext jobContext = context.jobContext();

        JSchClient client = null;
        try {
            FilePath[] configPaths = context.jobContext().workspacePath().list(relativeFilePath);
            if (configPaths == null || configPaths.length == 0) {
                context.logError(Messages.MarathonDeploymentCommand_configNotFoundAt(relativeFilePath));
                context.setDeploymentState(DeploymentState.HasError);
                return;
            }

            client = new JSchClient(host, Constants.DCOS_SSH_PORT, linuxAdminUsername, sshCredentials, context);

            for (FilePath configPath : configPaths) {
                String deployedFilename = "acsDep" + Calendar.getInstance().getTimeInMillis() + ".json";
                context.logStatus(Messages.MarathonDeploymentCommand_copyConfigFileTo(
                        configPath.toURI(), client.getHost(), deployedFilename));
                client.copyTo(
                        jobContext.replaceMacro(configPath.read(), context.isEnableConfigSubstitution()),
                        deployedFilename);

                String appId = JsonHelper.getId(configPath.read());
                //ignore if app does not exist
                context.logStatus(Messages.MarathonDeploymentCommand_deletingApp(appId));
                client.execRemote("curl -i -X DELETE http://localhost/marathon/v2/apps/" + appId);
                context.logStatus(Messages.MarathonDeploymentCommand_deployingApp(deployedFilename, appId));
                // NB. about "?force=true"
                // Sometimes the deployment gets rejected after the previous delete of the same application ID
                // with the following message:
                //
                // App is locked by one or more deployments. Override with the option '?force=true'.
                // View details at '/v2/deployments/<DEPLOYMENT_ID>'.
                client.execRemote("curl -i -H 'Content-Type: application/json' -d@"
                        + deployedFilename + " http://localhost/marathon/v2/apps?force=true");

                context.logStatus(Messages.MarathonDeploymentCommand_removeTempFile(deployedFilename));
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

    public interface IMarathonDeploymentCommandData extends IBaseCommandData {
        String getMgmtFQDN();

        String getLinuxAdminUsername();

        SSHUserPrivateKey getSshCredentials();

        String getConfigFilePaths();

        boolean isEnableConfigSubstitution();
    }
}
