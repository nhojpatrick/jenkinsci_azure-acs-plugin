/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */
package com.microsoft.jenkins.acs.commands;

import com.microsoft.azure.management.Azure;
import com.microsoft.azure.management.compute.ContainerService;
import com.microsoft.azure.management.compute.ContainerServiceOchestratorTypes;

public class GetContainserServiceInfoCommand implements ICommand<GetContainserServiceInfoCommand.IGetContainserServiceInfoCommandData> {
    @Override
    public void execute(IGetContainserServiceInfoCommandData context) {
        context.logStatus("Getting management public FQDN.");
        final Azure azureClient = context.getAzureClient();
        final String resourceGroupName = context.getResourceGroupName();
        final String containerServiceName = context.getContainerServiceName();

        ContainerService containerService = azureClient.containerServices().getByResourceGroup(context.getResourceGroupName(), context.getContainerServiceName());
        if (containerService == null) {
            context.logError(String.format("Cannot load Container Service %s from Resource Group %s", containerServiceName, resourceGroupName));
            return;
        }

        ContainerServiceOchestratorTypes orchestratorType = containerService.orchestratorType();
        context.logStatus("Container service orchestrator type: " + orchestratorType);
        context.setOrchestratorType(orchestratorType);

        final String fqdn = containerService.masterFqdn();
        context.logStatus("Management master FQDN: " + fqdn);
        context.setMgmtFQDN(fqdn);

        final String adminUser = containerService.linuxRootUsername();
        context.logStatus("Management admin username: " + adminUser);
        context.setLinuxRootUsername(adminUser);

        context.setDeploymentState(DeploymentState.Success);
    }

    public interface IGetContainserServiceInfoCommandData extends IBaseCommandData {
        void setMgmtFQDN(String mgmtFQDN);

        void setLinuxRootUsername(String linuxAdminUsername);

        void setOrchestratorType(ContainerServiceOchestratorTypes type);
    }
}
