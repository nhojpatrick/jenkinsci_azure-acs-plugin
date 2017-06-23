/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */
package com.microsoft.jenkins.acs.commands;

import com.microsoft.azure.management.Azure;
import com.microsoft.azure.management.compute.ContainerService;

public class GetPublicFQDNCommand implements ICommand<GetPublicFQDNCommand.IGetPublicFQDNCommandData> {
    @Override
    public void execute(GetPublicFQDNCommand.IGetPublicFQDNCommandData context) {
        context.logStatus("Getting management public FQDN.");
        final Azure azureClient = context.getAzureClient();
        final String resourceGroupName = context.getResourceGroupName();
        final String containerServiceName = context.getContainerServiceName();

        ContainerService containerService = azureClient.containerServices().getByResourceGroup(context.getResourceGroupName(), context.getContainerServiceName());
        if (containerService == null) {
            context.logError(String.format("Cannot load Container Service %s from Resource Group %s", containerServiceName, resourceGroupName));
            return;
        }

        final String fqdn = containerService.masterFqdn();
        context.logStatus("Management master FQDN: " + fqdn);
        context.setMgmtFQDN(fqdn);

        final String adminUser = containerService.linuxRootUsername();
        context.logStatus("Management admin username: " + adminUser);
        context.setLinuxRootUsername(adminUser);

        context.setDeploymentState(DeploymentState.Success);
    }

    public interface IGetPublicFQDNCommandData extends IBaseCommandData {
        void setMgmtFQDN(String mgmtFQDN);

        void setLinuxRootUsername(String linuxAdminUsername);
    }
}
