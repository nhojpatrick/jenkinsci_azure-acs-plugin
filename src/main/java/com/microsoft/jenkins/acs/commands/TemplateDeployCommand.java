/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */
package com.microsoft.jenkins.acs.commands;

import com.microsoft.jenkins.acs.exceptions.AzureCloudException;
import com.microsoft.jenkins.acs.services.AzureManagementServiceDelegate;
import com.microsoft.jenkins.acs.services.IARMTemplateServiceData;

public class TemplateDeployCommand implements ICommand<TemplateDeployCommand.ITemplateDeployCommandData> {
    public void execute(TemplateDeployCommand.ITemplateDeployCommandData context) {
        context.logStatus("Starting deployment");
        try {
            String deploymentName = AzureManagementServiceDelegate.deploy(context.getArmTemplateServiceData());
            context.setDeploymentState(DeploymentState.Success);
            context.setDeploymentName(deploymentName);
            context.logStatus("Deployment started.");
        } catch (AzureCloudException e) {
            context.logError("Error starting deployment:", e);
        }
    }

    public interface ITemplateDeployCommandData extends IBaseCommandData {
        IARMTemplateServiceData getArmTemplateServiceData();

        void setDeploymentName(String deploymentName);
    }
}
