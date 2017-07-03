/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs.commands;

import com.microsoft.azure.management.Azure;
import com.microsoft.azure.management.compute.ContainerService;
import com.microsoft.azure.management.compute.ContainerServiceOchestratorTypes;
import com.microsoft.jenkins.acs.Messages;

public class GetContainserServiceInfoCommand
        implements ICommand<GetContainserServiceInfoCommand.IGetContainserServiceInfoCommandData> {
    @Override
    public void execute(final IGetContainserServiceInfoCommandData context) {
        context.logStatus(Messages.GetContainserServiceInfoCommand_getFQDN());
        final Azure azureClient = context.getAzureClient();
        final String resourceGroupName = context.getResourceGroupName();
        final String containerServiceName = context.getContainerServiceName();

        ContainerService containerService =
                azureClient
                        .containerServices()
                        .getByResourceGroup(context.getResourceGroupName(), context.getContainerServiceName());
        if (containerService == null) {
            context.logError(
                    Messages.GetContainserServiceInfoCommand_containerServiceNotFound(
                            containerServiceName, resourceGroupName));
            return;
        }

        ContainerServiceOchestratorTypes orchestratorType = containerService.orchestratorType();
        context.logStatus(Messages.GetContainserServiceInfoCommand_orchestratorType(orchestratorType));

        ContainerServiceOchestratorTypes configured = context.getOrchestratorType();
        if (configured == null || orchestratorType != configured) {
            context.logError(Messages.GetContainserServiceInfoCommand_orchestratorTypeNotMatch(
                    containerServiceName, orchestratorType, configured
            ));
        }

        final String fqdn = containerService.masterFqdn();
        context.logStatus(Messages.GetContainserServiceInfoCommand_fqdn(fqdn));
        context.setMgmtFQDN(fqdn);

        final String adminUser = containerService.linuxRootUsername();
        context.logStatus(Messages.GetContainserServiceInfoCommand_adminUser(adminUser));
        context.setLinuxRootUsername(adminUser);

        context.setDeploymentState(DeploymentState.Success);
    }

    public interface IGetContainserServiceInfoCommandData extends IBaseCommandData {
        void setMgmtFQDN(String mgmtFQDN);

        void setLinuxRootUsername(String linuxAdminUsername);

        ContainerServiceOchestratorTypes getOrchestratorType();
    }
}
