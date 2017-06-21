/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */
package com.microsoft.jenkins.acs.services;

import com.microsoft.azure.management.Azure;
import com.microsoft.jenkins.acs.exceptions.AzureCloudException;

import com.fasterxml.jackson.databind.JsonNode;

public interface IARMTemplateServiceData {
	Azure getAzureClient();
	String getResourceGroupName();
	String getEmbeddedTemplateName();
	void configureTemplate(JsonNode tmp) throws IllegalAccessException, AzureCloudException;
}
