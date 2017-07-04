package com.microsoft.jenkins.acs.orchestrators;

import com.microsoft.azure.management.network.Protocol;
import com.microsoft.jenkins.acs.Messages;
import hudson.FilePath;
import hudson.Util;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SwarmDeploymentConfig extends DeploymentConfig {

    public SwarmDeploymentConfig(final FilePath[] configFiles) {
        super(configFiles);
    }

    @Override
    public String getResourcePrefix() {
        return "swarm";
    }

    @Override
    public List<ServicePort> getServicePorts() throws IOException, InvalidFormatException, InterruptedException {
        final ArrayList<ServicePort> servicePorts = new ArrayList<ServicePort>();

        final FilePath[] configFiles = getConfigFiles();
        for (final FilePath configFile : configFiles) {
            try (InputStream cfgFile = configFile.read()) {
                Yaml yaml = new Yaml();
                Map<String, Object> root = (Map<String, Object>) yaml.load(cfgFile);

                // For files that do not declare a version are considered the legacy version
                // See https://docs.docker.com/compose/compose-file/compose-versioning/
                boolean isLegacyVersion = !root.containsKey("version");
                Map<String, Object> services = null;
                if (isLegacyVersion) {
                    // For legacy version all services are declared at the root of the document
                    services = root;
                } else {
                    // For newer versions all services are declared under the `services` key
                    services = (Map<String, Object>) root.get("services");
                    if (services == null) {
                        throw new InvalidFormatException(Messages.SwarmDeploymentConfig_invalidConfigFormatNodeNotFound(
                                configFile.getRemote(), "services"));
                    }
                }

                for (Object service : services.values()) {
                    Object portsNode = ((Map) service).get("ports");
                    if (!(portsNode instanceof List)) {
                        continue;
                    }

                    List<Object> ports = (List<Object>) portsNode;
                    for (Object portNode : ports) {
                        if (portNode instanceof String) {
                            servicePorts.addAll(parsePortShortSyntax((String) portNode));
                        } else if (portNode instanceof Map) {
                            servicePorts.addAll(parsePortLongSyntax((Map) portNode));
                        } else {
                            throw new InvalidFormatException(
                                    Messages.SwarmDeploymentConfig_invalidPortDefinition(
                                            portNode.toString(), configFile.getRemote()));
                        }
                    }
                }
            }
        }

        return servicePorts;
    }

    /**
     * Modified from https://github.com/docker/docker-py/blob/master/docker/utils/ports.py#L3
     */
    private static final Pattern PATTERN_PORT_SPEC = Pattern.compile(""
        + "^"                                           // Match full string
        + "("                                           // External part
        + "((?<host>[a-fA-F\\d.:]+):)?"                 // Address
        + "(?<ext>[\\d]*)(-(?<extEnd>[\\d]+))?:"        // External range
        + ")?"
        + "(?<int>[\\d]+)(-(?<intEnd>[\\d]+))?"         // Internal range
        + "(?<proto>/(udp|tcp))?"                       // Protocol
        + "$"                                           // Match full string
    );

    /**
     * Parse ports in short syntax
     *
     * @param def Ports definition in short syntax
     * @return List of ServicePort
     * @throws InvalidFormatException
     * @see <a href="https://docs.docker.com/compose/compose-file/#ports">Docker Compose - Ports</a>
     */
    private List<ServicePort> parsePortShortSyntax(final String def) throws InvalidFormatException {
        final Matcher m = PATTERN_PORT_SPEC.matcher(def);
        if (!m.matches()) {
            throw new InvalidFormatException(Messages.SwarmDeploymentConfig_invalidPortSyntax(def));
        }

        final String interText = m.group("int");
        final String interEndText = m.group("intEnd");
        final String extText = m.group("ext");
        final String extEndText = m.group("extEnd");
        final String protocolText = Util.fixNull(m.group("proto"));

        final int inter = Integer.valueOf(interText);

        int interEnd = inter;
        if (interEndText != null) {
            interEnd = Integer.valueOf(interEndText);
        }

        int ext = inter;
        if (extText != null) {
            ext = Integer.valueOf(extText);
        }

        int extEnd;
        if (extEndText != null) {
            extEnd = Integer.valueOf(extEndText);
        } else {
            if (interEnd == inter) {
                // Mapping single port. For example, "80:8080"
                extEnd = ext;
            } else {
                // Mapping multiple ports. For example, "8080-8081"
                extEnd = interEnd;
            }
        }

        Protocol protocol = Protocol.TCP;
        if (protocolText.equalsIgnoreCase("/udp")) {
            protocol = Protocol.UDP;
        }

        if (extEnd - ext != interEnd - inter) {
            throw new InvalidFormatException(Messages.SwarmDeploymentConfig_portRangesDontMatchInLength(def));
        }

        final ArrayList<ServicePort> servicePorts = new ArrayList<ServicePort>();
        for (int p = ext; p <= extEnd; p++) {
            servicePorts.add(new ServicePort(p, inter + p - ext, protocol));
        }

        return servicePorts;
    }
    /**
     * Parse ports in long syntax
     *
     * @param node Node of port definition
     * @return List of ServicePort
     * @throws InvalidFormatException
     * @see <a href="https://docs.docker.com/compose/compose-file/#ports">Docker Compose - Ports</a>
     */
    private List<ServicePort> parsePortLongSyntax(final Map node) throws InvalidFormatException {
        final Object targetNode = node.get("target");
        if (targetNode == null || !(targetNode instanceof Integer)) {
            throw new InvalidFormatException(Messages.SwarmDeploymentConfig_noTargetPort());
        }

        final Object publishedNode = node.get("published");
        if (publishedNode == null || !(publishedNode instanceof Integer)) {
            throw new InvalidFormatException(Messages.SwarmDeploymentConfig_noPublishedPort());
        }

        final Object protocolNode = node.get("protocol");
        Protocol protocol = Protocol.TCP;
        if (protocolNode != null && protocolNode instanceof String) {
            final String protocolText = (String) protocolNode;
            if (protocolText.equalsIgnoreCase("udp")) {
                protocol = Protocol.UDP;
            }
        }

        return Arrays.asList(new ServicePort((Integer) publishedNode, (Integer) targetNode, protocol));
    }
}
