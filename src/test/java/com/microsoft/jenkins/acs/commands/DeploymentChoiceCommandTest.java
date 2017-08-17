/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs.commands;

import com.microsoft.azure.management.compute.ContainerServiceOchestratorTypes;
import com.microsoft.jenkins.acs.ACSDeploymentContext;
import com.microsoft.jenkins.azurecommons.command.CommandState;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Tests for {@link DeploymentChoiceCommand}.
 */
public class DeploymentChoiceCommandTest {
    @Test
    public void testNullType() {
        DeploymentChoiceCommand.IDeploymentChoiceCommandData context = prepareContext(null);
        new DeploymentChoiceCommand().execute(context);

        verify(context, times(1)).setCommandState(CommandState.HasError);
    }

    @Test
    public void testNotSupportedType() {
        DeploymentChoiceCommand.IDeploymentChoiceCommandData context = prepareContext(
                ContainerServiceOchestratorTypes.CUSTOM);
        new DeploymentChoiceCommand().execute(context);

        verify(context, times(1)).setCommandState(CommandState.HasError);
    }

    @Test
    public void testKubernetes() {
        testChoiceForType(ContainerServiceOchestratorTypes.KUBERNETES, KubernetesDeploymentCommand.class);
    }

    @Test
    public void testDcos() {
        testChoiceForType(ContainerServiceOchestratorTypes.DCOS, MarathonDeploymentCommand.class);
    }

    @Test
    public void testSwarm() {
        testChoiceForType(ContainerServiceOchestratorTypes.SWARM, SwarmDeploymentCommand.class);
    }

    private void testChoiceForType(
            final ContainerServiceOchestratorTypes orchestratorType,
            final Class clazz) {
        DeploymentChoiceCommand.IDeploymentChoiceCommandData context = prepareContext(
                orchestratorType);
        DeploymentChoiceCommand command = new DeploymentChoiceCommand();
        command.execute(context);

        verify(context, times(1)).setCommandState(CommandState.Success);

        assertEquals(clazz, command.nextCommand());
    }

    private DeploymentChoiceCommand.IDeploymentChoiceCommandData prepareContext(
            final ContainerServiceOchestratorTypes orchestratorType) {
        final DeploymentChoiceCommand.IDeploymentChoiceCommandData context = mock(ACSDeploymentContext.class);
        doReturn(orchestratorType).when(context).getOrchestratorType();

        doAnswer(new Answer() {
            @Override
            public Object answer(final InvocationOnMock invocationOnMock) throws Throwable {
                context.setCommandState(CommandState.HasError);
                return null;
            }
        }).when(context).logError(any(String.class));

        return context;
    }
}
