/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */
package com.microsoft.jenkins.acs.commands;

import com.microsoft.azure.management.Azure;
import com.microsoft.azure.management.resources.Deployments;
import com.microsoft.jenkins.acs.services.AzureManagementServiceDelegate;

public class TemplateMonitorCommand implements ICommand<TemplateMonitorCommand.ITemplateMonitorCommandData> {
    public void execute(TemplateMonitorCommand.ITemplateMonitorCommandData context) {
        String deploymentName = context.getDeploymentName();
        String resourceGroupName = context.getResourceGroupName();
        Deployments deployments = context.getAzureClient().deployments();
        boolean deploySuccess = AzureManagementServiceDelegate.monitor(deployments, resourceGroupName, deploymentName, context);
        if (deploySuccess) {
            context.setDeploymentState(DeploymentState.Success);
            context.logStatus(
                    String.format("Azure '%s' deployed successfully.", deploymentName));
        } else {
            context.logError(
                    String.format("Azure '%s' depoyment unsuccessfully.", deploymentName));
        }
    }

    public interface ITemplateMonitorCommandData extends IBaseCommandData {
        String getDeploymentName();

        String getResourceGroupName();

        Azure getAzureClient();
    }
}
