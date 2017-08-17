/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs.commands;

import com.microsoft.azure.management.compute.ContainerServiceOchestratorTypes;
import com.microsoft.jenkins.acs.Messages;
import com.microsoft.jenkins.acs.util.Constants;
import com.microsoft.jenkins.azurecommons.command.CommandState;
import com.microsoft.jenkins.azurecommons.command.IBaseCommandData;
import com.microsoft.jenkins.azurecommons.command.ICommand;
import com.microsoft.jenkins.azurecommons.command.INextCommandAware;

/**
 * Makes decision on which deployment method to be used based on the configuration.
 */
public class DeploymentChoiceCommand
        implements ICommand<DeploymentChoiceCommand.IDeploymentChoiceCommandData>, INextCommandAware {
    private ContainerServiceOchestratorTypes orchestratorType;

    @Override
    public void execute(IDeploymentChoiceCommandData context) {
        ContainerServiceOchestratorTypes type = context.getOrchestratorType();
        if (type == null) {
            context.logError(Messages.DeploymentChoiceCommand_orchestratorNotFound());
            return;
        }
        if (!Constants.SUPPORTED_ORCHESTRATOR.contains(type)) {
            context.logError(Messages.DeploymentChoiceCommand_orchestratorNotSupported(type));
            return;
        }
        this.orchestratorType = type;
        context.setCommandState(CommandState.Success);
    }

    @Override
    public Class nextCommand() {
        switch (orchestratorType) {
            case KUBERNETES:
                return KubernetesDeploymentCommand.class;
            case DCOS:
                return MarathonDeploymentCommand.class;
            case SWARM:
                return SwarmDeploymentCommand.class;
            default:
                throw new IllegalStateException(
                        Messages.DeploymentChoiceCommand_orchestratorNotSupported(orchestratorType));
        }
    }

    public interface IDeploymentChoiceCommandData extends IBaseCommandData {
        ContainerServiceOchestratorTypes getOrchestratorType();
    }
}
