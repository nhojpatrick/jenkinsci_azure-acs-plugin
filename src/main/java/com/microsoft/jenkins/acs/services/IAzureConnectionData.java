/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */
package com.microsoft.jenkins.acs.services;

public interface IAzureConnectionData {
	public String getSubscriptionId();
	public String getClientId();
	public String getClientSecret();
	public String getOauth2TokenEndpoint();
}
