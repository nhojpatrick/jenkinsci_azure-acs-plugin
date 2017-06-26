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
import com.microsoft.jenkins.acs.exceptions.AzureCloudException;
import com.microsoft.jenkins.acs.util.JsonHelper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;

public class EnablePortCommand implements ICommand<EnablePortCommand.IEnablePortCommandData> {
    @Override
    public void execute(IEnablePortCommandData context) {
        String marathonConfigFile = context.getConfigFilePaths();
        if (context.getOrchestratorType() != ContainerServiceOchestratorTypes.DCOS) {
            context.setDeploymentState(DeploymentState.Success);
            return;
        }

        Azure azureClient = context.getAzureClient();
        String resourceGroupName = context.getResourceGroupName();
        try {
            ArrayList<Integer> hostPorts =
                    JsonHelper.getHostPorts(marathonConfigFile);
            context.logStatus("Enabling ports");
            for (Integer hPort : hostPorts) {
                boolean retVal = createSecurityGroup(context, azureClient, resourceGroupName, hPort);
                if (retVal) {
                    retVal = createLoadBalancerRule(context, azureClient, resourceGroupName, hPort);
                    if (!retVal) {
                        throw new AzureCloudException("Error enabling port:" + hPort + ".  Unknown status of other ports.");
                    }
                } else {
                    throw new AzureCloudException("Error enabling port:" + hPort + ".  Unknown status of other ports.");
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
        context.logStatus("Creating security rule for port " + hostPort + " if needed.");
        boolean secGroupFound = false;
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
                    context.logStatus("Security rule for port " + hostPort + " found.");
                    secGroupFound = true;
                    break OUTER;
                }
            }
        }

        if (publicGroup == null) {
            return false;
        }

        if (!secGroupFound) {
            context.logStatus("Security rule for port " + hostPort + " not found.");
            maxPrio = maxPrio + 10;
            if (maxPrio > 4086) {
                context.logError("Exceeded max priority for inbound security rules.");
                throw new AzureCloudException("Exceeded max priority for inbound security rules.");
            }

            maxPrio = maxPrio + 10;
            String ruleName = "Allow_" + hostPort;
            context.logStatus("Creating Security rule for port " + hostPort + " with name:" + ruleName);

            publicGroup.update()
                    .defineRule(ruleName)
                    .allowInbound()
                    .fromAnyAddress()
                    .fromAnyPort()
                    .toAnyAddress()
                    .toPort(hostPort)
                    .withAnyProtocol()
                    .withDescription("Allow HTTP traffic from the Internet to Public Agents")
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
        context.logStatus("Creating load balancer rule for port " + hostPort + " if needed.");

        boolean ruleFound = false;
        LoadBalancer foundLoadBalancer = null;
        OUTER:
        for (LoadBalancer balancer : loadBalancers) {
            // TODO: check the logic here
            if (balancer.name().startsWith("dcos-agent-lb-")) {
                if (balancer.inner().backendAddressPools().size() != 1 ||
                        balancer.inner().frontendIPConfigurations().size() != 1) {
                    context.logError("Balancer configuration from template not matching previous configuration.");
                    throw new AzureCloudException("Balancer configuration from template not matching previous configuration.");
                }
                foundLoadBalancer = balancer;

                for (LoadBalancingRule rule : balancer.loadBalancingRules().values()) {
                    if (rule.frontendPort() == hostPort) {
                        context.logStatus("Load balancer rule for port " + hostPort + " found.");
                        ruleFound = true;
                        break OUTER;
                    }
                }
            }
        }

        if (foundLoadBalancer == null) {
            return false;
        }

        if (!ruleFound) {
            context.logStatus("Load balancer rule for port " + hostPort + " not found.");
            String ruleName = "JLBRuleHttp" + hostPort;
            context.logStatus("Creating load balancer rule for port " + hostPort + " with name:" + ruleName);

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
