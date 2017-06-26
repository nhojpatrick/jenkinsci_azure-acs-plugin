package com.microsoft.jenkins.acs.commands;

import com.microsoft.azure.management.compute.ContainerServiceOchestratorTypes;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Makes decision on which deployment method to be used based on the configuration.
 */
public class DeploymentChoiceCommand implements ICommand<DeploymentChoiceCommand.IDeploymentChoiceCommandData>, INextCommandAware {
    private static final Set<ContainerServiceOchestratorTypes> SUPPORTED_ORCHESTRATOR = new HashSet<>(Arrays.asList(
            ContainerServiceOchestratorTypes.KUBERNETES,
            ContainerServiceOchestratorTypes.DCOS
    ));

    private ContainerServiceOchestratorTypes orchestratorType;

    @Override
    public void execute(IDeploymentChoiceCommandData context) {
        ContainerServiceOchestratorTypes type = context.getOrchestratorType();
        if (type == null) {
            context.logError("Container service's orchestrator type was not found");
            return;
        }
        if (!SUPPORTED_ORCHESTRATOR.contains(type)) {
            context.logError("Deployment of container service with orchestrator type " + type + " is not supported");
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
                throw new IllegalStateException("Unsupported container service orchestrator type: " + orchestratorType);
        }
    }

    public interface IDeploymentChoiceCommandData extends IBaseCommandData {
        ContainerServiceOchestratorTypes getOrchestratorType();
    }
}
