package com.microsoft.jenkins.acs.orchestrators;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.azure.management.network.Protocol;
import com.microsoft.jenkins.acs.Messages;
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
    public List<ServicePort> getServicePorts() throws IOException, InterruptedException, InvalidFormatException {
        ArrayList<ServicePort> servicePorts = new ArrayList<ServicePort>();

        for (final FilePath configFile : getConfigFiles()) {
            try (InputStream cfgFile = configFile.read()) {
                final ObjectMapper mapper = new ObjectMapper();

                JsonNode node = null;
                try {
                    node = mapper.readTree(cfgFile);
                } catch (JsonProcessingException e) {
                    throw new InvalidFormatException(e);
                }

                // Walk down the tree to find the `container.docker.portMappings` node
                final String[] fieldKeys = new String[]{"container", "docker", "portMappings"};
                for (int i = 0; i < fieldKeys.length; i++) {
                    final String fieldKey = fieldKeys[i];
                    node = node.get(fieldKey);
                    if (node == null) {
                        throw new InvalidFormatException(
                                Messages.MarathonDeploymentConfig_invalidConfigFormatNodeNotFound(
                                        configFile.getRemote(), fieldKey));
                    }
                }

                Iterator<JsonNode> elements = node.elements();
                while (elements.hasNext()) {
                    JsonNode element = elements.next();

                    JsonNode containerPortNode = element.get("containerPort");
                    if (containerPortNode == null) {
                        throw new InvalidFormatException(
                                Messages.MarathonDeploymentConfig_invalidConfigFormatNodeNotFound(
                                        configFile.getRemote(), "container.docker.portMapping[].containerPort"));
                    }
                    int containerPort = containerPortNode.asInt();

                    JsonNode hostPortNode = element.get("hostPort");
                    if (hostPortNode == null) {
                        throw new InvalidFormatException(
                                Messages.MarathonDeploymentConfig_invalidConfigFormatNodeNotFound(
                                        configFile.getRemote(), "container.docker.portMapping[].hostPort"));
                    }
                    int hostPort = hostPortNode.asInt();

                    Protocol protocol = Protocol.TCP;
                    JsonNode protocolNode = element.get("protocol");
                    if (protocolNode != null && protocolNode.asText().equalsIgnoreCase("udp")) {
                        protocol = Protocol.UDP;
                    }

                    servicePorts.add(new ServicePort(hostPort, containerPort, protocol));
                }
            }
        }

        return servicePorts;
    }
}
