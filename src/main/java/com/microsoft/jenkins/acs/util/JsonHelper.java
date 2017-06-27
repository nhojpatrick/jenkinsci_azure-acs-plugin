/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */
package com.microsoft.jenkins.acs.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;

public class JsonHelper {
    public static ArrayList<Integer> getHostPorts(String marathonConfigFile) throws IOException {
        ArrayList<Integer> hostPorts = new ArrayList<>();
        try (InputStream marathonFile = new FileInputStream(marathonConfigFile)) {
            final ObjectMapper mapper = new ObjectMapper();
            JsonNode parentNode = mapper.readTree(marathonFile);
            JsonNode node = parentNode.get("container").get("docker").get("portMappings");
            Iterator<JsonNode> elements = node.elements();
            while (elements.hasNext()) {
                JsonNode element = elements.next();
                hostPorts.add(element.get("hostPort").asInt());
            }
        }

        return hostPorts;
    }

    public static String getId(String marathonConfigFile) throws IOException {
        try (InputStream marathonFile = new FileInputStream(marathonConfigFile)) {
            final ObjectMapper mapper = new ObjectMapper();
            JsonNode parentNode = mapper.readTree(marathonFile);
            return parentNode.get("id").textValue();
        }
    }
}
