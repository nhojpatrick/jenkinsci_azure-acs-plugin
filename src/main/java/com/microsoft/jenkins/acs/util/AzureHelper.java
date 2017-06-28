/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs.util;

import com.microsoft.azure.credentials.AzureTokenCredentials;
import com.microsoft.azure.management.Azure;
import com.microsoft.azure.util.AzureCredentials;
import com.microsoft.jenkins.acs.Messages;
import org.apache.commons.lang.StringUtils;

/**
 * Helper methods on the Azure related constructs.
 */
public final class AzureHelper {

    public static Azure buildClientFromServicePrincipal(final AzureCredentials.ServicePrincipal servicePrincipal) {
        AzureTokenCredentials credentials = DependencyMigration.buildAzureTokenCredentials(servicePrincipal);
        return Azure.authenticate(credentials).withSubscription(servicePrincipal.getSubscriptionId());
    }

    public static Azure buildClientFromCredentialsId(final String azureCredentialsId) {
        AzureCredentials.ServicePrincipal servicePrincipal = AzureCredentials.getServicePrincipal(azureCredentialsId);
        if (StringUtils.isEmpty(servicePrincipal.getClientId())) {
            throw new IllegalArgumentException(Messages.AzureHelper_servicePrincipalNotFound(azureCredentialsId));
        }

        return buildClientFromServicePrincipal(servicePrincipal);
    }

    private AzureHelper() {
        // hide constructor
    }
}
