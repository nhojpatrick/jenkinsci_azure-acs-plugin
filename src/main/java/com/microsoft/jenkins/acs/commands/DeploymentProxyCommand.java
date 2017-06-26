package com.microsoft.jenkins.acs.commands;

import com.microsoft.azure.management.compute.ContainerServiceOchestratorTypes;

/**
 * Acts as a proxy to execute the actual command based on the container orchestrator type.
 */
public class DeploymentProxyCommand implements ICommand<DeploymentProxyCommand.IDeploymentProxyCommandData> {
    @Override
    public void execute(IDeploymentProxyCommandData context) {
        ContainerServiceOchestratorTypes orchestratorType = context.getOrchestratorType();

        switch (orchestratorType) {
            case DCOS:
                new MarathonDeploymentCommand().execute((MarathonDeploymentCommand.IMarathonDeploymentCommandData)context);
                break;
            case KUBERNETES:
                new KubernetesDeploymentCommand().execute((KubernetesDeploymentCommand.IKubernetesDeploymentCommandData)context);
                break;
            default:
                context.logError("Unsupported container service orchestrator type: " + orchestratorType);
                context.setDeploymentState(DeploymentState.UnSuccessful);
                break;
        }
    }

    public interface IDeploymentProxyCommandData extends IBaseCommandData {
        ContainerServiceOchestratorTypes getOrchestratorType();
    }
}
