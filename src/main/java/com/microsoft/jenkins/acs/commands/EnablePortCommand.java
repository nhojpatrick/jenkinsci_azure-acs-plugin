/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */
package com.microsoft.jenkins.acs.commands;

import com.microsoft.azure.PagedList;
import com.microsoft.azure.management.Azure;
import com.microsoft.azure.management.compute.ContainerServiceOchestratorTypes;
import com.microsoft.azure.management.network.LoadBalancer;
import com.microsoft.azure.management.network.LoadBalancingRule;
import com.microsoft.azure.management.network.NetworkSecurityGroup;
import com.microsoft.azure.management.network.NetworkSecurityRule;
import com.microsoft.azure.management.network.TransportProtocol;
import com.microsoft.jenkins.acs.Messages;
import com.microsoft.jenkins.acs.exceptions.AzureCloudException;
import com.microsoft.jenkins.acs.util.JsonHelper;
import hudson.FilePath;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;

public class EnablePortCommand implements ICommand<EnablePortCommand.IEnablePortCommandData> {
    @Override
    public void execute(IEnablePortCommandData context) {
        String relativeFilePaths = context.getConfigFilePaths();
        if (context.getOrchestratorType() != ContainerServiceOchestratorTypes.DCOS) {
            context.setDeploymentState(DeploymentState.Success);
            return;
        }

        Azure azureClient = context.getAzureClient();
        String resourceGroupName = context.getResourceGroupName();
        try {
            FilePath[] configPaths = context.jobContext().workspacePath().list(relativeFilePaths);

            for (FilePath configPath : configPaths) {
                ArrayList<Integer> hostPorts = JsonHelper.getHostPorts(configPath.read());
                context.logStatus(Messages.EnablePortCommand_enabling(hostPorts));
                for (Integer hPort : hostPorts) {
                    boolean retVal = createSecurityGroup(context, azureClient, resourceGroupName, hPort);
                    if (retVal) {
                        retVal = createLoadBalancerRule(context, azureClient, resourceGroupName, hPort);
                        if (!retVal) {
                            throw new AzureCloudException(Messages.EnablePortCommand_errorEnabling(hPort));
                        }
                    } else {
                        throw new AzureCloudException(Messages.EnablePortCommand_errorEnabling(hPort));
                    }
                }
            }

            context.setDeploymentState(DeploymentState.Success);
        } catch (InterruptedException | IOException | AzureCloudException e) {
            context.logError(e);
        }
    }

    private static boolean createSecurityGroup(
            IBaseCommandData context, Azure azureClient, String resourceGroupName, int hostPort)
            throws InterruptedException, IOException, AzureCloudException {

        PagedList<NetworkSecurityGroup> securityGroups = azureClient.networkSecurityGroups().listByResourceGroup(resourceGroupName);
        context.logStatus(Messages.EnablePortCommand_createSecurityGroupIfNeeded(hostPort));
        boolean securityRuleFound = false;
        int maxPrio = Integer.MIN_VALUE;
        NetworkSecurityGroup publicGroup = null;
        OUTER:
        for (NetworkSecurityGroup group : securityGroups) {
            if (!group.name().startsWith("dcos-agent-public-nsg-")) {
                continue;
            }

            publicGroup = group;
            for (Map.Entry<String, NetworkSecurityRule> entry : group.securityRules().entrySet()) {
                NetworkSecurityRule rule = entry.getValue();
                int prio = rule.priority();
                if (prio > maxPrio) {
                    maxPrio = prio;
                }

                if (rule.destinationPortRange().equals(hostPort + "")) {
                    context.logStatus(Messages.EnablePortCommand_securityRuleFound(hostPort));
                    securityRuleFound = true;
                    break OUTER;
                }
            }
        }

        if (publicGroup == null) {
            // TODO: create one
            return false;
        }

        if (!securityRuleFound) {
            context.logStatus(Messages.EnablePortCommand_securityRuleNotFound(hostPort));
            maxPrio = maxPrio + 10;
            if (maxPrio > 4086) {
                context.logError(Messages.EnablePortCommand_exceedMaxPriority());
                throw new AzureCloudException(Messages.EnablePortCommand_exceedMaxPriority());
            }

            String ruleName = "Allow_" + hostPort;
            context.logStatus(Messages.EnablePortCommand_creatingRule(hostPort, ruleName));

            publicGroup.update()
                    .defineRule(ruleName)
                    .allowInbound()
                    .fromAnyAddress()
                    .fromAnyPort()
                    .toAnyAddress()
                    .toPort(hostPort)
                    .withAnyProtocol()
                    .withDescription(Messages.EnablePortCommand_allowTraffic(hostPort))
                    .withPriority(maxPrio)
                    .attach()
                    .apply();
        }
        return true;
    }

    private static boolean createLoadBalancerRule(
            IBaseCommandData context, Azure azureClient, String resourceGroupName, int hostPort)
            throws InterruptedException, IOException, AzureCloudException {

        PagedList<LoadBalancer> loadBalancers = azureClient.loadBalancers().listByResourceGroup(resourceGroupName);
        context.logStatus(Messages.EnablePortCommand_createLBIfNeeded(hostPort));

        boolean ruleFound = false;
        LoadBalancer foundLoadBalancer = null;
        OUTER:
        for (LoadBalancer balancer : loadBalancers) {
            if (balancer.name().startsWith("dcos-agent-lb-")) {
                if (balancer.backends().size() != 1 ||
                        balancer.frontends().size() != 1) {
                    context.logError(Messages.EnablePortCommand_missMatch());
                    throw new AzureCloudException(Messages.EnablePortCommand_missMatch());
                }
                foundLoadBalancer = balancer;

                for (LoadBalancingRule rule : balancer.loadBalancingRules().values()) {
                    if (rule.frontendPort() == hostPort) {
                        context.logStatus(Messages.EnablePortCommand_lbFound(hostPort));
                        ruleFound = true;
                        break OUTER;
                    }
                }
            }
        }

        if (foundLoadBalancer == null) {
            // TODO: create one
            return false;
        }

        if (!ruleFound) {
            context.logStatus(Messages.EnablePortCommand_lBNotFound(hostPort));
            String ruleName = "JLBRuleHttp" + hostPort;
            context.logStatus(Messages.EnablePortCommand_creatingLB(hostPort, ruleName));

            String probeName = "tcpProbe_" + hostPort;

            foundLoadBalancer.update()

                    .defineTcpProbe(probeName)
                    .withPort(hostPort)
                    .attach()

                    .defineLoadBalancingRule(ruleName)
                    .withProtocol(TransportProtocol.TCP)
                    .withFrontend(foundLoadBalancer.frontends().values().iterator().next().name())
                    .withFrontendPort(hostPort)
                    .withProbe(probeName)
                    .withBackend(foundLoadBalancer.backends().values().iterator().next().name())
                    .attach()

                    .apply();
        }

        return true;
    }

    public interface IEnablePortCommandData extends IBaseCommandData {
        String getConfigFilePaths();

        ContainerServiceOchestratorTypes getOrchestratorType();
    }
}
