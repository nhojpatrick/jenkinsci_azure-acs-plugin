/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */
package org.jenkinsci.plugins.microsoft.util;

import java.io.IOException;
import java.util.ArrayList;

import org.jenkinsci.plugins.microsoft.commands.IBaseCommandData;
import org.jenkinsci.plugins.microsoft.exceptions.AzureCloudException;

import com.microsoft.azure.management.network.NetworkResourceProviderClient;
import com.microsoft.azure.management.network.models.LoadBalancer;
import com.microsoft.azure.management.network.models.LoadBalancerListResponse;
import com.microsoft.azure.management.network.models.LoadBalancerPutResponse;
import com.microsoft.azure.management.network.models.LoadBalancingRule;
import com.microsoft.azure.management.network.models.LoadDistribution;
import com.microsoft.azure.management.network.models.NetworkSecurityGroup;
import com.microsoft.azure.management.network.models.NetworkSecurityGroupListResponse;
import com.microsoft.azure.management.network.models.NetworkSecurityGroupPutResponse;
import com.microsoft.azure.management.network.models.PublicIpAddress;
import com.microsoft.azure.management.network.models.PublicIpAddressListResponse;
import com.microsoft.azure.management.network.models.SecurityRule;
import com.microsoft.windowsazure.exception.ServiceException;

public class NetworkResourceProviderHelper {
	public static String getMgmtPublicIPFQDN(NetworkResourceProviderClient client, String dnsNamePrefix) 
			throws IOException, ServiceException, AzureCloudException {
        ArrayList<PublicIpAddress> ipAddresses = new ArrayList<PublicIpAddress>();
		PublicIpAddressListResponse ipAddressResponse = client.getPublicIpAddressesOperations().list(dnsNamePrefix);
		ipAddresses = ipAddressResponse.getPublicIpAddresses();
		if(ipAddresses.size() != 2) {
			throw new AzureCloudException("Not able to find FQDN for management public IP address.");
		}

		int index = ipAddresses.get(0).getDnsSettings().getFqdn().contains("@" + dnsNamePrefix + "mgmt.") ? 0 : 1;
		return ipAddresses.get(index).getDnsSettings().getFqdn();
	}
	
    public static boolean createSecurityGroup(
    		IBaseCommandData context, NetworkResourceProviderClient client,
    		String dnsNamePrefix, int hostPort) 
    		throws InterruptedException, IOException, ServiceException, AzureCloudException {
		NetworkSecurityGroupListResponse nsResponse = client.getNetworkSecurityGroupsOperations().list(dnsNamePrefix);
		ArrayList<NetworkSecurityGroup> groups = nsResponse.getNetworkSecurityGroups();
		context.logStatus("Creating security rule for port " + hostPort + " if needed.");
		for(NetworkSecurityGroup group : groups) {
			if(group.getName().startsWith("dcos-agent-public-nsg-")) {
				String groupName = group.getName();
				boolean secGroupFound = false;
				int maxPrio = Integer.MIN_VALUE;
				for(SecurityRule sRule : group.getSecurityRules()) {
					int prio = sRule.getPriority();
					if(prio > maxPrio) {
						maxPrio = prio;
					}
					
					if(sRule.getDestinationPortRange().equals(hostPort + "")) {
						context.logStatus("Security rule for port " + hostPort + " found.");
						secGroupFound = true;
						break;
					}
				}
				
				if(!secGroupFound) {
					context.logStatus("Security rule for port " + hostPort + " not found.");
										maxPrio = maxPrio + 10;
					if(maxPrio > 4086) {
						context.logError("Exceeded max priority for inbound security rules.");
						throw new AzureCloudException("Exceeded max priority for inbound security rules.");
					}
					
					maxPrio = maxPrio + 10;
					String ruleName = "Allow_" + hostPort;
					context.logStatus("Creating Security rule for port " + hostPort + " with name:" + ruleName);
					SecurityRule sRule = new SecurityRule();
					sRule.setDirection("Inbound");
					sRule.setAccess("Allow");
					sRule.setName(ruleName);
					sRule.setDescription("Allow HTTP traffic from the Internet to Public Agents");
					sRule.setProtocol("*");
					sRule.setPriority(maxPrio);
					sRule.setSourceAddressPrefix("Internet");
					sRule.setSourcePortRange("*");
					sRule.setDestinationAddressPrefix("*");
					sRule.setDestinationPortRange(hostPort + "");
					group.getSecurityRules().add(sRule);
					NetworkSecurityGroupPutResponse putResponse = client.getNetworkSecurityGroupsOperations().beginCreateOrUpdating(dnsNamePrefix,  
							group.getName(),  group);								
					if(putResponse.getStatusCode() > 299) {
						context.logError("Error creating security rule. Status code was:" + putResponse.getStatusCode());
			        	return false;	
					}
					
					boolean done = false;
					while(!done) {
						NetworkSecurityGroupListResponse nsResponseCheck = client.getNetworkSecurityGroupsOperations().list(dnsNamePrefix);
						ArrayList<NetworkSecurityGroup> groupsCheck = nsResponseCheck.getNetworkSecurityGroups();
						context.logStatus("Checking if security rule with name '" + ruleName + "' has been created");
						for(NetworkSecurityGroup groupCheck : groupsCheck) {
							if(groupCheck.getName().equals(groupName)) {
								for(SecurityRule sRuleCheck : groupCheck.getSecurityRules()) {
									if(sRuleCheck.getName().equals(ruleName)) {
										context.logStatus("Security rule with name '" + ruleName + "' found");
										done = true;
										break;
									}
								}	
								
								if(!done) {
									Thread.sleep(20000);
								}
								
								break;
							}
						}
					}
				}
				
				break;
			}			
		}

		return true;
    }
    
    public static boolean createLoadBalancerRule(
    		IBaseCommandData context, NetworkResourceProviderClient client,
    		String dnsNamePrefix, int hostPort) 
    		throws InterruptedException, IOException, ServiceException, AzureCloudException {
		LoadBalancerListResponse response = client.getLoadBalancersOperations().list(dnsNamePrefix);
		ArrayList<LoadBalancer> balancers = response.getLoadBalancers();
		context.logStatus("Creating load balancer rule for port " + hostPort + " if needed.");
		for(LoadBalancer balancer : balancers) {
			if(balancer.getName().startsWith("dcos-agent-lb-")) {
				String balancerName = balancer.getName();
				if(balancer.getBackendAddressPools().size() != 1 ||
					balancer.getFrontendIpConfigurations().size() != 1) {
					context.logError("Balancer configuration from template not matching previous configuration.");
					throw new AzureCloudException("Balancer configuration from template not matching previous configuration.");
				}
				
				boolean ruleFound = false;
				for(LoadBalancingRule rule : balancer.getLoadBalancingRules()) {
					if(rule.getFrontendPort() == hostPort) {
						context.logStatus("Load balancer rule for port " + hostPort + " found.");
						ruleFound = true;
					}
				}
				
				if(!ruleFound) {
					context.logStatus("Load balancer rule for port " + hostPort + " not found.");
					LoadBalancingRule rule = new LoadBalancingRule();
					String ruleName = "JLBRuleHttp" + hostPort;
					context.logStatus("Creating load balancer rule for port " + hostPort + " with name:" + ruleName);
					rule.setLoadDistribution(LoadDistribution.DEFAULT);
					rule.setProtocol("Tcp");
					rule.setFrontendPort(hostPort);
					rule.setBackendPort(hostPort);
					rule.setBackendAddressPool(balancer.getBackendAddressPools().get(0));
					rule.setIdleTimeoutInMinutes(5);
					rule.setName(ruleName);
					rule.setFrontendIPConfiguration(balancer.getFrontendIpConfigurations().get(0));
					
					balancer.getLoadBalancingRules().add(rule);
					LoadBalancerPutResponse putResponse = client.getLoadBalancersOperations().beginCreateOrUpdating(dnsNamePrefix,  
						balancer.getName(),  balancer);
					if(putResponse.getStatusCode() > 299) {
						context.logError("Error creating load balancer rule. Status code was:" + putResponse.getStatusCode());
			        	return false;	
					}

					boolean done = false;
					while(!done) {
						LoadBalancerListResponse lbResponseCheck = client.getLoadBalancersOperations().list(dnsNamePrefix);
						ArrayList<LoadBalancer> balancersCheck = lbResponseCheck.getLoadBalancers();
						context.logStatus("Checking if load balancer rule with name '" + ruleName + "' has been created");
						for(LoadBalancer balancerCheck : balancersCheck) {
							if(balancerCheck.getName().equals(balancerName)) {
								for(LoadBalancingRule ruleCheck : balancer.getLoadBalancingRules()) {
									if(ruleCheck.getName().equals(ruleName)) {
										context.logStatus("Load balancer rule with name '" + ruleName + "' found");
										done = true;
										break;
									}
								}	
								
								if(!done) {
									Thread.sleep(20000);
								}
								
								break;
							}
						}
					}
				}
			}
		}
		
		return true;
    }    
}
