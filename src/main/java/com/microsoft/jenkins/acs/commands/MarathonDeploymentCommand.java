/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */
package com.microsoft.jenkins.acs.commands;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.jcraft.jsch.JSchException;
import com.microsoft.jenkins.acs.util.Constants;
import com.microsoft.jenkins.acs.util.JSchClient;
import com.microsoft.jenkins.acs.util.JsonHelper;
import hudson.FilePath;

import java.io.File;
import java.io.IOException;
import java.util.Calendar;

public class MarathonDeploymentCommand implements ICommand<MarathonDeploymentCommand.IMarathonDeploymentCommandData> {
    @Override
    public void execute(MarathonDeploymentCommand.IMarathonDeploymentCommandData context) {
        String host = context.getMgmtFQDN();
        SSHUserPrivateKey sshCredentials = context.getSshCredentials();
        String linuxAdminUsername = context.getLinuxAdminUsername();
        String relativeFilePath = context.getConfigFilePaths();

        JSchClient client = null;
        try {
            FilePath[] configPaths = context.jobContext().getWorkspace().list(relativeFilePath);
            if (configPaths == null || configPaths.length == 0) {
                context.logError("No configuration found at: " + relativeFilePath);
                context.setDeploymentState(DeploymentState.UnSuccessful);
                return;
            }

            client = new JSchClient(host, Constants.DCOS_SSH_PORT, linuxAdminUsername, sshCredentials, context);

            for (FilePath configPath : configPaths) {
                String deployedFilename = "acsDep" + Calendar.getInstance().getTimeInMillis() + ".json";
                context.logStatus("Copying marathon file to remote file: " + deployedFilename);
                client.copyTo(new File(configPath.getRemote()), deployedFilename);

                String appId = JsonHelper.getId(configPath.getRemote());
                //ignore if app does not exist
                context.logStatus(String.format("Deleting application with appId: '%s' if it exists", appId));
                client.execRemote("curl -X DELETE http://localhost/marathon/v2/apps/" + appId);
                context.logStatus(String.format("Deploying file '%s' with appId %s to marathon.", deployedFilename, appId));
                client.execRemote("curl -i -H 'Content-Type: application/json' -d@" + deployedFilename + " http://localhost/marathon/v2/apps");
            }
            context.setDeploymentState(DeploymentState.Success);
        } catch (JSchException | IOException | InterruptedException e) {
            context.logError("Error deploying application to marathon:", e);
            context.setDeploymentState(DeploymentState.UnSuccessful);
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
    }
}
