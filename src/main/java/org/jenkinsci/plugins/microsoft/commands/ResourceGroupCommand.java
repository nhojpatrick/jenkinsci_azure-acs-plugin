/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */
package org.jenkinsci.plugins.microsoft.commands;

import java.io.IOException;
import java.net.URISyntaxException;

import org.jenkinsci.plugins.microsoft.commands.DeploymentState;

import com.microsoft.azure.management.resources.ResourceManagementClient;
import com.microsoft.azure.management.resources.models.ResourceGroup;
import com.microsoft.azure.management.resources.models.ResourceGroupCreateOrUpdateResult;
import com.microsoft.windowsazure.exception.ServiceException;

public class ResourceGroupCommand implements ICommand<ResourceGroupCommand.IResourceGroupCommandData> {
	public void execute(ResourceGroupCommand.IResourceGroupCommandData context) {
		try {
			String resourceGroupName = context.getResourceGroupName();
			String location = context.getLocation();
	        context.logStatus(String.format("Creating resource group '%s' if it does not exist", resourceGroupName));
	        ResourceManagementClient rmc = context.getResourceClient();
	        ResourceGroupCreateOrUpdateResult rcResult;
			rcResult = rmc.getResourceGroupsOperations().createOrUpdate(resourceGroupName,  new ResourceGroup(location));
			if(rcResult.getStatusCode() > 299) {
	        	context.logError("Error creating resource group. Status code was:" + rcResult.getStatusCode());
	        	return;
	        }

			context.setDeploymentState(DeploymentState.Success);
		} catch (IOException | ServiceException | URISyntaxException e) {
			context.logError("Error creating resource group:", e);
		}
	}
	
	public interface IResourceGroupCommandData extends IBaseCommandData {
		public String getResourceGroupName();
		public String getLocation();
		public ResourceManagementClient getResourceClient();
	}
}
