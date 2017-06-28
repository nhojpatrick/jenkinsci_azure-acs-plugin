package com.microsoft.jenkins.acs.commands;

import com.microsoft.azure.management.compute.ContainerServiceOchestratorTypes;
import com.microsoft.jenkins.acs.Messages;
import com.microsoft.jenkins.acs.util.Constants;

/**
 * Makes decision on which deployment method to be used based on the configuration.
 */
public class DeploymentChoiceCommand
        implements ICommand<DeploymentChoiceCommand.IDeploymentChoiceCommandData>, INextCommandAware {
    private ContainerServiceOchestratorTypes orchestratorType;

    @Override
    public void execute(final IDeploymentChoiceCommandData context) {
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
        context.setDeploymentState(DeploymentState.Success);
    }

    @Override
    public Class<? extends ICommand<? extends IBaseCommandData>> getNextCommand() {
        switch (orchestratorType) {
            case KUBERNETES:
                return KubernetesDeploymentCommand.class;
            case DCOS:
                return MarathonDeploymentCommand.class;
            default:
                throw new IllegalStateException(
                        Messages.DeploymentChoiceCommand_orchestratorNotSupported(orchestratorType));
        }
    }

    public interface IDeploymentChoiceCommandData extends IBaseCommandData {
        ContainerServiceOchestratorTypes getOrchestratorType();
    }
}
