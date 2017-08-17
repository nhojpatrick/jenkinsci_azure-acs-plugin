/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;

public final class JsonHelper {
    public static String getMarathonAppId(InputStream in) throws IOException {
        try {
            final ObjectMapper mapper = new ObjectMapper();
            JsonNode parentNode = mapper.readTree(in);
            return parentNode.get("id").textValue();
        } finally {
            in.close();
        }
    }

    private JsonHelper() {
        // hide constructor
    }
}
