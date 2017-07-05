/*
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
import com.microsoft.jenkins.acs.util.Constants;
import com.microsoft.jenkins.acs.util.JsonHelper;
import hudson.FilePath;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;

public class EnablePortCommand implements ICommand<EnablePortCommand.IEnablePortCommandData> {
    @Override
    public void execute(final IEnablePortCommandData context) {
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
                    boolean retVal = createSecurityRule(context, azureClient, resourceGroupName, hPort);
                    if (!retVal) {
                        return;
                    }
                    retVal = createLoadBalancerRule(context, azureClient, resourceGroupName, hPort);
                    if (!retVal) {
                        return;
                    }
                }
            }

            context.setDeploymentState(DeploymentState.Success);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            context.logError(e);
        } catch (IOException e) {
            context.logError(e);
        }
    }

    private static boolean createSecurityRule(
            final IBaseCommandData context,
            final Azure azureClient,
            final String resourceGroupName,
            final int hostPort) throws IOException {

        PagedList<NetworkSecurityGroup> securityGroups =
                azureClient.networkSecurityGroups().listByResourceGroup(resourceGroupName);
        context.logStatus(Messages.EnablePortCommand_createSecurityRuleIfNeeded(hostPort));
        boolean securityRuleFound = false;
        int maxPriorityNumber = Integer.MIN_VALUE;
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
                if (prio > maxPriorityNumber) {
                    maxPriorityNumber = prio;
                }

                if (rule.destinationPortRange().equals(hostPort + "")) {
                    context.logStatus(Messages.EnablePortCommand_securityRuleFound(hostPort));
                    securityRuleFound = true;
                    break OUTER;
                }
            }
        }

        if (publicGroup == null) {
            context.logError(Messages.EnablePortCommand_securityGroupNotFound());
            return false;
        }

        if (!securityRuleFound) {
            maxPriorityNumber = maxPriorityNumber + Constants.PRIORITY_STEP;
            if (maxPriorityNumber > Constants.LOWEST_PRIORITY) {
                context.logError(Messages.EnablePortCommand_exceedMaxPriority());
                return false;
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
                    .withPriority(maxPriorityNumber)
                    .attach()
                    .apply();
        }
        return true;
    }

    private static boolean createLoadBalancerRule(
            final IBaseCommandData context,
            final Azure azureClient,
            final String resourceGroupName,
            final int hostPort) throws IOException {

        PagedList<LoadBalancer> loadBalancers = azureClient.loadBalancers().listByResourceGroup(resourceGroupName);
        context.logStatus(Messages.EnablePortCommand_createLBIfNeeded(hostPort));

        boolean ruleFound = false;
        LoadBalancer foundLoadBalancer = null;
        OUTER:
        for (LoadBalancer balancer : loadBalancers) {
            if (balancer.name().startsWith("dcos-agent-lb-")) {
                if (balancer.backends().size() != 1
                        || balancer.frontends().size() != 1) {
                    context.logError(Messages.EnablePortCommand_missMatch());
                    return false;
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
            context.logError(Messages.EnablePortCommand_lbNotFound());
            return false;
        }

        if (!ruleFound) {
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
