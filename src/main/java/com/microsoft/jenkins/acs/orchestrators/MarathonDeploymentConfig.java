package com.microsoft.jenkins.acs.orchestrators;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.azure.management.network.Protocol;
import hudson.FilePath;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class MarathonDeploymentConfig extends DeploymentConfig {

    public MarathonDeploymentConfig(final FilePath[] configFiles) {
        super(configFiles);
    }

    @Override
    public String getResourcePrefix() {
        return "dcos";
    }

    @Override
    public List<ServicePort> getServicePorts() throws IOException, InterruptedException {
        ArrayList<ServicePort> servicePorts = new ArrayList<ServicePort>();

        for (final FilePath configFile : getConfigFiles()) {
            try (InputStream cfgFile = configFile.read()) {
                final ObjectMapper mapper = new ObjectMapper();
                JsonNode parentNode = mapper.readTree(cfgFile);
                JsonNode node = parentNode.get("container").get("docker").get("portMappings");
                Iterator<JsonNode> elements = node.elements();
                while (elements.hasNext()) {
                    JsonNode element = elements.next();
                    int port = element.get("hostPort").asInt();
                    servicePorts.add(new ServicePort(port, port, Protocol.TCP));
                }
            }
        }

        return servicePorts;
    }
}
