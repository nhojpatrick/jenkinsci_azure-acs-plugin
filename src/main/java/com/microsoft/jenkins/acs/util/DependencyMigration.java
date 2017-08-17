/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs.util;

import com.microsoft.azure.AzureEnvironment;
import com.microsoft.azure.credentials.ApplicationTokenCredentials;
import com.microsoft.azure.credentials.AzureTokenCredentials;
import com.microsoft.azure.util.AzureCredentials;
import org.apache.commons.lang3.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * Utility methods to help with the migration of the dependencies.
 */
public final class DependencyMigration {
    public static AzureTokenCredentials buildAzureTokenCredentials(
            AzureCredentials.ServicePrincipal servicePrincipal) {
        return new ApplicationTokenCredentials(
                servicePrincipal.getClientId(),
                servicePrincipal.getTenant(),
                servicePrincipal.getClientSecret(),
                buildAzureEnvironment(servicePrincipal)
        );
    }

    /**
     * Build {@link AzureEnvironment} (since Azure SDK 1.1.0) from {@link AzureCredentials.ServicePrincipal} (since
     * azure-credentials 1.1).
     *
     * @param servicePrincipal the service principal provided by the Azure Credentials plugin
     * @return The {@link AzureEnvironment} instance suitable for the Azure SDK used in this plugin
     */
    public static AzureEnvironment buildAzureEnvironment(
            AzureCredentials.ServicePrincipal servicePrincipal) {
        final String managementEndpoint = servicePrincipal.getServiceManagementURL();
        final String activeDirectoryEndpoint = servicePrincipal.getAuthenticationEndpoint();
        final String resourceManagerEndpoint = servicePrincipal.getResourceManagerEndpoint();
        final String graphEndpoint = servicePrincipal.getGraphEndpoint();

        AzureEnvironment env = resolveEnvironment(
                managementEndpoint, activeDirectoryEndpoint, resourceManagerEndpoint, graphEndpoint);
        if (env == null) {
            Map<String, String> endpoints = new HashMap<>(AzureEnvironment.AZURE.endpoints());
            endpoints.put("managementEndpointUrl", managementEndpoint);
            endpoints.put("activeDirectoryEndpointUrl", activeDirectoryEndpoint);
            endpoints.put("resourceManagerEndpointUrl", resourceManagerEndpoint);
            endpoints.put("activeDirectoryGraphResourceId", graphEndpoint);
            env = new AzureEnvironment(endpoints);
        }

        return env;
    }

    private static AzureEnvironment resolveEnvironment(
            String managementEndpointUrl,
            String activeDirectoryEndpointUrl,
            String resourceManagerEndpointUrl,
            String graphEndpointUrl) {
        for (AzureEnvironment env : AzureEnvironment.knownEnvironments()) {
            if (sameUrl(env.managementEndpoint(), managementEndpointUrl)
                    && sameUrl(env.activeDirectoryEndpoint(), activeDirectoryEndpointUrl)
                    && sameUrl(env.resourceManagerEndpoint(), resourceManagerEndpointUrl)
                    && sameUrl(env.graphEndpoint(), graphEndpointUrl)) {
                return env;
            }
        }
        return null;
    }

    private static boolean sameUrl(String base, String target) {
        if (StringUtils.isBlank(target)) {
            return false;
        }
        String enrichedBase = base;
        if (!base.endsWith("/")) {
            enrichedBase = base + '/';
        }
        String enrichedTarget = target;
        if (!target.endsWith("/")) {
            enrichedTarget = target + '/';
        }
        return enrichedBase.equals(enrichedTarget);
    }

    private DependencyMigration() {
        // hide constructor
    }
}
