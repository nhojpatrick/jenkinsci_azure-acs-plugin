/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs.commands;

import com.microsoft.azure.management.Azure;
import com.microsoft.azure.management.compute.ContainerService;
import com.microsoft.azure.management.compute.ContainerServiceOchestratorTypes;
import com.microsoft.azure.management.compute.ContainerServices;
import com.microsoft.jenkins.acs.ACSDeploymentContext;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Tests for the {@link GetContainerServiceInfoCommand}
 */
public class GetContainerServiceInfoCommandTest {
    private static final String RESOURCE_GROUP_NAME = "resource-group";
    private static final String CONTAINER_SERVICE_NAME = "container-service";
    private static final ContainerServiceOchestratorTypes ORCHESTRATOR_TYPE =
            ContainerServiceOchestratorTypes.KUBERNETES;
    private static final String FQDN = "fqdn.test";
    private static final String ROOT_USER = "azureuser";

    @Test
    public void testExecuteSuccess() {
        GetContainerServiceInfoCommand.IGetContainerServiceInfoCommandData context =
                prepareContext(true, ORCHESTRATOR_TYPE);

        new GetContainerServiceInfoCommand().execute(context);

        verify(context, times(1)).setMgmtFQDN(FQDN);
        verify(context, times(1)).setLinuxRootUsername(ROOT_USER);

        verify(context, times(1)).setDeploymentState(DeploymentState.Success);
    }

    @Test
    public void testContainerServiceNotFound() {
        GetContainerServiceInfoCommand.IGetContainerServiceInfoCommandData context =
                prepareContext(false, ORCHESTRATOR_TYPE);
        new GetContainerServiceInfoCommand().execute(context);

        verify(context, never()).setMgmtFQDN(any(String.class));
        verify(context, never()).setLinuxRootUsername(any(String.class));
        verify(context, times(1)).setDeploymentState(DeploymentState.HasError);
    }

    @Test
    public void testOrchestratorTypeNotMatch() {
        GetContainerServiceInfoCommand.IGetContainerServiceInfoCommandData context =
                prepareContext(true, ContainerServiceOchestratorTypes.DCOS);
        new GetContainerServiceInfoCommand().execute(context);

        verify(context, never()).setMgmtFQDN(any(String.class));
        verify(context, never()).setLinuxRootUsername(any(String.class));
        verify(context, times(1)).setDeploymentState(DeploymentState.HasError);
    }

    private GetContainerServiceInfoCommand.IGetContainerServiceInfoCommandData prepareContext(
            final boolean returnContainerService,
            final ContainerServiceOchestratorTypes configuredOrchestratorType) {
        final GetContainerServiceInfoCommand.IGetContainerServiceInfoCommandData context =
                mock(ACSDeploymentContext.class);

        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                context.setDeploymentState(DeploymentState.HasError);
                return null;
            }
        }).when(context).logError(any(String.class));

        Azure azureClient = mock(Azure.class);
        doReturn(azureClient).when(context).getAzureClient();
        doReturn(RESOURCE_GROUP_NAME).when(context).getResourceGroupName();
        doReturn(CONTAINER_SERVICE_NAME).when(context).getContainerServiceName();

        ContainerServices containerServices = mock(ContainerServices.class);
        doReturn(containerServices).when(azureClient).containerServices();

        if (!returnContainerService) {
            return context;
        }

        ContainerService containerService = mock(ContainerService.class);
        doReturn(containerService).when(containerServices)
                .getByResourceGroup(RESOURCE_GROUP_NAME, CONTAINER_SERVICE_NAME);

        doReturn(ORCHESTRATOR_TYPE).when(containerService).orchestratorType();
        doReturn(configuredOrchestratorType).when(context).getOrchestratorType();

        doReturn(FQDN).when(containerService).masterFqdn();
        doReturn(ROOT_USER).when(containerService).linuxRootUsername();

        return context;
    }
}
