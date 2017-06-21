/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */
package com.microsoft.jenkins.acs.util;

import com.microsoft.aad.adal4j.AuthenticationResult;

import java.io.Serializable;
import java.util.Date;

public class AccessToken implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String subscriptionId;

    private final String serviceManagementUrl;

    private final String token;

    private final long expiration;

    AccessToken(
            final String subscriptionId, final String serviceManagementUrl, final AuthenticationResult authres) {
        this.subscriptionId = subscriptionId;
        this.serviceManagementUrl = serviceManagementUrl;
        this.token = authres.getAccessToken();
        this.expiration = authres.getExpiresOn();
    }

    public Date getExpirationDate() {
        return new Date(expiration);
    }

    public boolean isExpiring() {
        return expiration < System.currentTimeMillis();
    }

    @Override
    public String toString() {
        return token;
    }
}
