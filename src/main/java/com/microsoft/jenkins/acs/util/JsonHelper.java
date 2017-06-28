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
import java.util.ArrayList;
import java.util.Iterator;

public final class JsonHelper {
    public static ArrayList<Integer> getHostPorts(final InputStream in) throws IOException {
        ArrayList<Integer> hostPorts = new ArrayList<>();
        try {
            final ObjectMapper mapper = new ObjectMapper();
            JsonNode parentNode = mapper.readTree(in);
            JsonNode node = parentNode.get("container").get("docker").get("portMappings");
            Iterator<JsonNode> elements = node.elements();
            while (elements.hasNext()) {
                JsonNode element = elements.next();
                hostPorts.add(element.get("hostPort").asInt());
            }
        } finally {
            in.close();
        }

        return hostPorts;
    }

    public static String getId(final InputStream in) throws IOException {
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
