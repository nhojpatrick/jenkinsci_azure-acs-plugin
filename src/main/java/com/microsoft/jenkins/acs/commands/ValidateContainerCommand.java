/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */
package com.microsoft.jenkins.acs.commands;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;

import com.microsoft.azure.management.resources.ResourceManagementClient;
import com.microsoft.azure.management.resources.models.GenericResourceExtended;
import com.microsoft.azure.management.resources.models.ResourceListParameters;
import com.microsoft.azure.management.resources.models.ResourceListResult;
import com.microsoft.windowsazure.exception.ServiceException;

public class ValidateContainerCommand implements ICommand<ValidateContainerCommand.IValidateContainerCommandData> {
	public void execute(ValidateContainerCommand.IValidateContainerCommandData context) {
		try {
			String dnsNamePrefix = context.getDnsNamePrefix();
	        ResourceManagementClient rmc = context.getResourceClient();
			ResourceListParameters parameters = new ResourceListParameters();
			parameters.setResourceGroupName(dnsNamePrefix);			
			ResourceListResult result1 = rmc.getResourcesOperations().list(parameters);
			ArrayList<GenericResourceExtended> resources = result1.getResources();
	        context.logStatus(
	        		String.format("Checking if the Azure Container Service with name 'containerservice-%s' exist.", dnsNamePrefix));
	        boolean found = false;
			for(GenericResourceExtended resource : resources) {
				String name = resource.getName();
				if(name.equals("containerservice-"  + dnsNamePrefix)){
			        context.logStatus(
			        		String.format("Azure Container Service with name 'containerservice-%s' found.", dnsNamePrefix));
					context.setDeploymentState(DeploymentState.Success);
					found = true;
					break;
				}				
			}
			
			if(!found) {
				context.logStatus(
						String.format("Azure Container Service 'containerservice-%s' not found.", dnsNamePrefix));
				context.setDeploymentState(DeploymentState.UnSuccessful);
			}
		} catch (IOException | ServiceException | URISyntaxException e) {
			context.logError("Error creating resource group:", e);
		}
	}
	
	public interface IValidateContainerCommandData extends IBaseCommandData {
		public String getDnsNamePrefix();
		public String getLocation();
		public ResourceManagementClient getResourceClient();
	}
}
