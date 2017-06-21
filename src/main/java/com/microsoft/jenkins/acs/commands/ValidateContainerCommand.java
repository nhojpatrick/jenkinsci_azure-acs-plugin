/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */
package com.microsoft.jenkins.acs.commands;

import com.microsoft.azure.PagedList;
import com.microsoft.azure.management.Azure;
import com.microsoft.azure.management.compute.ContainerService;

public class ValidateContainerCommand implements ICommand<ValidateContainerCommand.IValidateContainerCommandData> {
    public void execute(ValidateContainerCommand.IValidateContainerCommandData context) {
        try {
            String dnsNamePrefix = context.getDnsNamePrefix();
            Azure azureClient = context.getAzureClient();
            String resourceGroupName = context.getResourceGroupName();

            context.logStatus(
                    String.format("Checking if the Azure Container Service with name 'containerservice-%s' exist.", dnsNamePrefix));
            PagedList<ContainerService> containerServices = azureClient.containerServices().listByResourceGroup(resourceGroupName);
            boolean found = false;
            for (ContainerService containerService : containerServices) {
                String name = containerService.name();
                // TODO: check logic here
                if (name.equals("containerservice-" + dnsNamePrefix)) {
                    context.logStatus(
                            String.format("Azure Container Service with name 'containerservice-%s' found.", dnsNamePrefix));
                    context.setDeploymentState(DeploymentState.Success);
                    found = true;
                    break;
                }
            }

            if (!found) {
                context.logStatus(
                        String.format("Azure Container Service 'containerservice-%s' not found.", dnsNamePrefix));
                context.setDeploymentState(DeploymentState.UnSuccessful);
            }
        } catch (RuntimeException e) {
            context.logError("Error creating resource group:", e);
        }
    }

    public interface IValidateContainerCommandData extends IBaseCommandData {
        String getDnsNamePrefix();

        String getLocation();

        Azure getAzureClient();

        String getResourceGroupName();
    }
}
