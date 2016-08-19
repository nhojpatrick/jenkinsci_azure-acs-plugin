/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */
package org.jenkinsci.plugins.microsoft.commands;

import java.io.IOException;
import java.util.ArrayList;

import org.jenkinsci.plugins.microsoft.commands.DeploymentState;
import org.jenkinsci.plugins.microsoft.exceptions.AzureCloudException;
import org.jenkinsci.plugins.microsoft.util.JsonHelper;
import org.jenkinsci.plugins.microsoft.util.NetworkResourceProviderHelper;

import com.microsoft.azure.management.network.NetworkResourceProviderClient;
import com.microsoft.azure.management.resources.ResourceManagementClient;
import com.microsoft.windowsazure.exception.ServiceException;

public class EnablePortCommand implements ICommand<EnablePortCommand.IEnablePortCommandData> {
	public void execute(IEnablePortCommandData context) {
		String marathonConfigFile = context.getMarathonConfigFile(); 
		NetworkResourceProviderClient client = context.getNetworkClient();
		String dnsNamePrefix = context.getDnsNamePrefix();
		try {
			ArrayList<Integer> hostPorts = 
	    			JsonHelper.getHostPorts(marathonConfigFile);
	        context.logStatus("Enabling ports");
	    	for(Integer hPort : hostPorts) {
				boolean retVal = NetworkResourceProviderHelper.createSecurityGroup(context, client, dnsNamePrefix, hPort);
				if(retVal) {
					retVal = NetworkResourceProviderHelper.createLoadBalancerRule(context, client, dnsNamePrefix, hPort);
					if(!retVal) {
						throw new AzureCloudException("Error enabling port:" + hPort + ".  Unknown status of other ports.");
					}
				}else {
					throw new AzureCloudException("Error enabling port:" + hPort + ".  Unknown status of other ports.");
				}
	    	}
	    	
	    	context.setDeploymentState(DeploymentState.Success);
		} catch (InterruptedException | IOException | ServiceException | AzureCloudException e) {
			context.logError(e);
		}
	}
	
	public interface IEnablePortCommandData extends IBaseCommandData {
		public String getDnsNamePrefix();
		public String getLocation();
		public String getMarathonConfigFile();
		public NetworkResourceProviderClient getNetworkClient();
		public ResourceManagementClient getResourceClient();
	}
}
