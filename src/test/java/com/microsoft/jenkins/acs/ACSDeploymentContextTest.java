package com.microsoft.jenkins.acs;

import com.microsoft.jenkins.acs.commands.DeploymentChoiceCommand;
import com.microsoft.jenkins.acs.commands.EnablePortCommand;
import com.microsoft.jenkins.acs.commands.GetContainerServiceInfoCommand;
import com.microsoft.jenkins.acs.commands.KubernetesDeploymentCommand;
import com.microsoft.jenkins.acs.commands.MarathonDeploymentCommand;
import com.microsoft.jenkins.acs.commands.SwarmDeploymentCommand;
import org.junit.Test;

public class ACSDeploymentContextTest {

    @Test
    public void commandDataCast() {
        ACSDeploymentContext context = new ACSDeploymentContext("", "", "", "", "");

        GetContainerServiceInfoCommand.IGetContainerServiceInfoCommandData getInfoData =
                (GetContainerServiceInfoCommand.IGetContainerServiceInfoCommandData) context;
        DeploymentChoiceCommand.IDeploymentChoiceCommandData choiceData =
                (DeploymentChoiceCommand.IDeploymentChoiceCommandData) context;
        MarathonDeploymentCommand.IMarathonDeploymentCommandData marathonData =
                (MarathonDeploymentCommand.IMarathonDeploymentCommandData) context;
        KubernetesDeploymentCommand.IKubernetesDeploymentCommandData kuberData =
                (KubernetesDeploymentCommand.IKubernetesDeploymentCommandData) context;
        SwarmDeploymentCommand.ISwarmDeploymentCommandData swarmData =
                (SwarmDeploymentCommand.ISwarmDeploymentCommandData) context;
        EnablePortCommand.IEnablePortCommandData enablePortData =
                (EnablePortCommand.IEnablePortCommandData) context;
    }
}
