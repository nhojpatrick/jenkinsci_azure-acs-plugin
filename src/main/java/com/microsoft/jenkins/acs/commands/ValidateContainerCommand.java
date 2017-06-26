/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */
package com.microsoft.jenkins.acs.commands;

import com.microsoft.azure.management.Azure;
import com.microsoft.azure.management.compute.ContainerService;
import com.microsoft.azure.management.compute.ContainerServiceOchestratorTypes;

public class ValidateContainerCommand implements ICommand<ValidateContainerCommand.IValidateContainerCommandData> {
    @Override
    public void execute(ValidateContainerCommand.IValidateContainerCommandData context) {
        try {
            String dnsNamePrefix = context.getDnsNamePrefix();
            Azure azureClient = context.getAzureClient();
            String resourceGroupName = context.getResourceGroupName();
            final String name = "containerservice-" + dnsNamePrefix;

            context.logStatus(
                    String.format("Checking if the Azure Container Service with name 'containerservice-%s' exist.", dnsNamePrefix));
            ContainerService containerService = azureClient.containerServices().getByResourceGroup(resourceGroupName, name);
            if (containerService == null) {
                context.logStatus(
                        String.format("Azure Container Service '%s' not found.", name));
                context.setDeploymentState(DeploymentState.UnSuccessful);
            } else if (containerService.orchestratorType() != ContainerServiceOchestratorTypes.DCOS) {
                context.logStatus(
                        String.format("Azure Container Service '%s' is not a DC/OS container.", name));
                context.setDeploymentState(DeploymentState.UnSuccessful);
            } else {
                context.logStatus(
                        String.format("Azure Container Service with name '%s' found.", name));
                context.setDeploymentState(DeploymentState.Success);
            }
        } catch (RuntimeException e) {
            context.logError("Error creating resource group:", e);
        }
    }

    public interface IValidateContainerCommandData extends IBaseCommandData {
        String getDnsNamePrefix();
    }
}
