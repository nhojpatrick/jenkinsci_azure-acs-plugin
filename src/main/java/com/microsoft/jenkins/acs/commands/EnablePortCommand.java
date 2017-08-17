/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs.commands;

import com.microsoft.azure.PagedList;
import com.microsoft.azure.management.Azure;
import com.microsoft.azure.management.network.LoadBalancer;
import com.microsoft.azure.management.network.LoadBalancerBackend;
import com.microsoft.azure.management.network.LoadBalancerFrontend;
import com.microsoft.azure.management.network.LoadBalancingRule;
import com.microsoft.azure.management.network.LoadDistribution;
import com.microsoft.azure.management.network.NetworkSecurityGroup;
import com.microsoft.azure.management.network.NetworkSecurityRule;
import com.microsoft.azure.management.network.SecurityRuleAccess;
import com.microsoft.azure.management.network.SecurityRuleDirection;
import com.microsoft.jenkins.acs.Messages;
import com.microsoft.jenkins.acs.orchestrators.DeploymentConfig;
import com.microsoft.jenkins.acs.orchestrators.ServicePort;
import com.microsoft.jenkins.acs.util.Constants;
import com.microsoft.jenkins.azurecommons.command.CommandState;
import com.microsoft.jenkins.azurecommons.command.IBaseCommandData;
import com.microsoft.jenkins.azurecommons.command.ICommand;

import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public class EnablePortCommand implements ICommand<EnablePortCommand.IEnablePortCommandData> {

    public static final int LOAD_BALANCER_IDLE_TIMEOUT_IN_MINUTES = 5;

    static final class InvalidConfigException extends Exception {
        InvalidConfigException(String message) {
            super(message);
        }
    }

    @Override
    public void execute(IEnablePortCommandData context) {
        Azure azureClient = context.getAzureClient();
        String resourceGroupName = context.getResourceGroupName();
        try {
            final DeploymentConfig config = context.getDeploymentConfig();
            if (config == null) {
                context.logError(Messages.DeploymentConfig_invalidConfig());
                return;
            }

            final String resourcePrefix = config.getResourcePrefix();
            final List<ServicePort> servicePorts = config.getServicePorts();

            createSecurityRules(context, azureClient, resourceGroupName, resourcePrefix, servicePorts);

            createLoadBalancerRules(context, azureClient, resourceGroupName, resourcePrefix, servicePorts);

            context.setCommandState(CommandState.Success);
        } catch (IOException | InvalidConfigException | DeploymentConfig.InvalidFormatException e) {
            context.logError(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            context.logError(e);
        }
    }

    static int filterPortsToOpen(
            IBaseCommandData context,
            Collection<NetworkSecurityRule> rules,
            Set<Integer> portsToOpen) throws InvalidConfigException {
        int maxPriority = Integer.MIN_VALUE;
        for (NetworkSecurityRule rule : rules) {
            final int priority = rule.priority();
            if (priority > maxPriority) {
                maxPriority = priority;
            }

            if (!SecurityRuleDirection.INBOUND.equals(rule.direction())) {
                // Ignore outbound rules
                continue;
            }

            if (!SecurityRuleAccess.ALLOW.equals(rule.access())) {
                // If user denied a port explicitly, we honor this rule and won't disable or modify it automatically
                // even it might be in our ports-to-open list, as it's hard for us to guess the user's intention without
                // knowledge of network topology and application details. So we just ignore it here.
                // If we create a conflicted allow rule later, it should have a higher priority number, which means
                // lower priority. It will have no affect in the worst case.
                continue;
            }

            final String ruleDestPortRange = rule.destinationPortRange();
            if (ruleDestPortRange.equals("*")) {
                // Already allow all
                context.logStatus(Messages.EnablePortCommand_securityRuleAlreadyAllowAll(
                        rule.name(), ruleDestPortRange));

                // No ports need to open
                portsToOpen.clear();

                break;
            } else if (ruleDestPortRange.contains("-")) {
                // Range
                final String[] parts = ruleDestPortRange.split("-", 2);
                int portStart = 0;
                int portEnd = 0;
                try {
                    portStart = Integer.valueOf(parts[0]);
                    portEnd = Integer.valueOf(parts[1]);
                } catch (NumberFormatException ex) {
                    throw new InvalidConfigException(
                            Messages.EnablePortCommand_securityRuleInvalidDestinationPortRange(ruleDestPortRange));
                }

                Iterator<Integer> it = portsToOpen.iterator();
                while (it.hasNext()) {
                    final int port = it.next();
                    if (port >= portStart && port <= portEnd) {
                        // Port already allowed
                        context.logStatus(Messages.EnablePortCommand_securityRuleAlreadyAllowSingle(
                                rule.name(), ruleDestPortRange, String.valueOf(port)));
                        it.remove();
                    }
                }
            } else {
                // Single
                final int port = Integer.valueOf(ruleDestPortRange);
                if (portsToOpen.remove(port)) {
                    context.logStatus(Messages.EnablePortCommand_securityRuleAlreadyAllowSingle(
                            rule.name(), ruleDestPortRange, String.valueOf(port)));
                }
            }
        }

        return maxPriority;
    }

    static void createSecurityRules(
            IBaseCommandData context,
            Azure azureClient,
            String resourceGroupName,
            String resourcePrefix,
            List<ServicePort> servicePorts) throws IOException, InvalidConfigException {

        if (servicePorts.isEmpty()) {
            return;
        }

        Set<Integer> portsToOpen = new HashSet<Integer>();
        for (ServicePort servicePort : servicePorts) {
            portsToOpen.add(servicePort.getHostPort());
        }

        final PagedList<NetworkSecurityGroup> nsgs =
                azureClient.networkSecurityGroups().listByResourceGroup(resourceGroupName);

        // Find security group for public agents
        NetworkSecurityGroup nsgPublicAgent = null;
        for (NetworkSecurityGroup nsg : nsgs) {
            if (nsg.name().startsWith(resourcePrefix + "-agent-public-nsg-")) {
                nsgPublicAgent = nsg;
                break;
            }
        }

        if (nsgPublicAgent == null) {
            // Do nothing if security group not found
            context.logStatus(Messages.EnablePortCommand_securityGroupNotFound());
            return;
        }

        int maxPriority = filterPortsToOpen(context, nsgPublicAgent.securityRules().values(), portsToOpen);

        // Create security rules for ports not opened
        final NetworkSecurityGroup.Update update = nsgPublicAgent.update();
        for (int port : portsToOpen) {
            context.logStatus(Messages.EnablePortCommand_securityRuleNotFound(String.valueOf(port)));

            maxPriority = maxPriority + Constants.PRIORITY_STEP;
            if (maxPriority > Constants.LOWEST_PRIORITY) {
                throw new InvalidConfigException(Messages.EnablePortCommand_exceedMaxPriority());
            }

            final String ruleName = "Allow_" + port;
            context.logStatus(Messages.EnablePortCommand_creatingRule(String.valueOf(port), ruleName));

            update.defineRule(ruleName)
                    .allowInbound()
                    .fromAddress("Internet")
                    .fromAnyPort()
                    .toAnyAddress()
                    .toPort(port)
                    .withAnyProtocol()
                    .withDescription(Messages.EnablePortCommand_allowTraffic(String.valueOf(port)))
                    .withPriority(maxPriority)
                    .attach();
        }

        update.apply();
    }

    static void createLoadBalancerRules(
            IBaseCommandData context,
            Azure azureClient,
            String resourceGroupName,
            String resourcePrefix,
            List<ServicePort> servicePorts) throws IOException, InvalidConfigException {

        if (servicePorts.isEmpty()) {
            return;
        }

        final PagedList<LoadBalancer> balancers = azureClient.loadBalancers().listByResourceGroup(resourceGroupName);

        LoadBalancer loadBalancer = null;
        for (LoadBalancer balancer : balancers) {
            final String balancerName = balancer.name();
            if (balancerName.startsWith(resourcePrefix + "-agent-lb-")) {
                if (balancer.backends().size() != 1 || balancer.frontends().size() != 1) {
                    throw new InvalidConfigException(Messages.EnablePortCommand_missMatch());
                }

                loadBalancer = balancer;
                break;
            }
        }

        if (loadBalancer == null) {
            // Do nothing if load balancer not found
            context.logStatus(Messages.EnablePortCommand_lbNotFound());
            return;
        }

        final LoadBalancerFrontend frontend = loadBalancer.frontends().values().iterator().next();
        final LoadBalancerBackend backend = loadBalancer.backends().values().iterator().next();
        final LoadBalancer.Update update = loadBalancer.update();

        for (ServicePort servicePort : servicePorts) {
            boolean ruleFound = false;
            for (LoadBalancingRule rule : loadBalancer.loadBalancingRules().values()) {
                if (servicePort.matchesLoadBalancingRule(rule)) {
                    context.logStatus(Messages.EnablePortCommand_lbFound(
                            String.valueOf(servicePort.getHostPort()), servicePort.getProtocol()));
                    ruleFound = true;
                    break;
                }
            }

            if (!ruleFound) {
                final String ruleName = "JLBRule" + servicePort.getProtocol().toString() + servicePort.getHostPort();
                context.logStatus(Messages.EnablePortCommand_creatingLB(
                        String.valueOf(servicePort.getHostPort()), ruleName));

                // Unfortunately there is no probe type of UDP, but it's mandatory. So always use TCP probe.
                final String probeName = "tcpPort" + servicePort.getHostPort() + "Probe";

                update
                        .defineTcpProbe(probeName)
                        .withPort(servicePort.getHostPort())
                        .attach()
                        .defineLoadBalancingRule(ruleName)
                        .withProtocol(servicePort.getTransportProtocol())
                        .withFrontend(frontend.name())
                        .withFrontendPort(servicePort.getHostPort())
                        .withProbe(probeName)
                        .withBackend(backend.name())
                        .withBackendPort(servicePort.getHostPort())
                        .withIdleTimeoutInMinutes(LOAD_BALANCER_IDLE_TIMEOUT_IN_MINUTES)
                        .withLoadDistribution(LoadDistribution.DEFAULT)
                        .attach();

            }
        }

        update.apply();
    }

    public interface IEnablePortCommandData extends IBaseCommandData {
        Azure getAzureClient();

        String getResourceGroupName();

        DeploymentConfig getDeploymentConfig() throws IOException, InterruptedException;
    }
}
