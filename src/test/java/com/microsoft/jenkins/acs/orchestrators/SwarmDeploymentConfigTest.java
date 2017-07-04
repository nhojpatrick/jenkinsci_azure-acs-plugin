package com.microsoft.jenkins.acs.orchestrators;


import com.microsoft.azure.management.network.Protocol;
import hudson.FilePath;
import org.apache.commons.io.FileUtils;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

public class SwarmDeploymentConfigTest {

    private void assertServicePorts(String config, List<ServicePort> expServicePorts) throws IOException, DeploymentConfig.InvalidFormatException, InterruptedException {
        final File file = File.createTempFile("tst-acs-", ".yml");
        file.deleteOnExit();
        FileUtils.write(file, config, "UTF-8");

        SwarmDeploymentConfig configFile = new SwarmDeploymentConfig(new FilePath[]{new FilePath(file)});
        List<ServicePort> servicePorts = configFile.getServicePorts();

        Assert.assertEquals(expServicePorts.size(), servicePorts.size());
        for (int i = 0; i < servicePorts.size(); i++) {
            Assert.assertEquals(expServicePorts.get(i), servicePorts.get(i));
        }
    }

    @Test
    public void getServicePortsShortSyntax() throws IOException, DeploymentConfig.InvalidFormatException, InterruptedException {
        final String config = ""
            + "nginx:\n"
            + "  image: nginx\n"
            + "  ports:\n"
            + "    - \"9090:80\"\n"
            + "tomcat:\n"
            + "  image: tomcat\n"
            + "  ports:\n"
            + "    - \"8080\"\n"
            + "    - \"8081-8082\"\n"
            + "    - \"8083:8083\"\n"
            + "    - \"8084-8085:18084-18085\"\n"
            + "    - \"127.0.0.1:8086:8086\"\n"
            + "    - \"127.0.0.1:8087-8088:18087-18088\"\n"
            + "    - \"8089-8090/udp\"\n";
        final List<ServicePort> expServicePorts = Arrays.asList(
            new ServicePort(9090, 80, Protocol.TCP),
            new ServicePort(8080, 8080, Protocol.TCP),
            new ServicePort(8081, 8081, Protocol.TCP),
            new ServicePort(8082, 8082, Protocol.TCP),
            new ServicePort(8083, 8083, Protocol.TCP),
            new ServicePort(8084, 18084, Protocol.TCP),
            new ServicePort(8085, 18085, Protocol.TCP),
            new ServicePort(8086, 8086, Protocol.TCP),
            new ServicePort(8087, 18087, Protocol.TCP),
            new ServicePort(8088, 18088, Protocol.TCP),
            new ServicePort(8089, 8089, Protocol.UDP),
            new ServicePort(8090, 8090, Protocol.UDP)
        );
        assertServicePorts(config, expServicePorts);
    }

    @Test
    public void getServicePortsLongSyntax() throws IOException, DeploymentConfig.InvalidFormatException, InterruptedException {
        final String config = ""
                + "nginx:\n"
                + "  image: nginx\n"
                + "  ports:\n"
                + "    - \"9090:80\"\n"
                + "tomcat:\n"
                + "  image: tomcat\n"
                + "  ports:\n"
                + "    - target: 8080\n"
                + "      published: 8080\n"
                + "      protocol: tcp\n"
                + "      mode: host\n"
                + "    - target: 8081\n"
                + "      published: 18081\n"
                + "      protocol: udp\n"
                + "      mode: host\n";
        final List<ServicePort> expServicePorts = Arrays.asList(
                new ServicePort(9090, 80, Protocol.TCP),
                new ServicePort(8080, 8080, Protocol.TCP),
                new ServicePort(18081, 8081, Protocol.UDP)
        );
        assertServicePorts(config, expServicePorts);
    }

    @Test
    public void getServicePortsNonLegacyVersion() throws IOException, DeploymentConfig.InvalidFormatException, InterruptedException {
        final String config = ""
                + "version: '2'\n"
                + "services:\n"
                + "  nginx:\n"
                + "    image: nginx\n"
                + "    ports:\n"
                + "      - \"9090:80\"\n"
                + "  tomcat:\n"
                + "    image: tomcat\n"
                + "    ports:\n"
                + "      - target: 8080\n"
                + "        published: 8080\n"
                + "        protocol: tcp\n"
                + "        mode: host\n";
        final List<ServicePort> expServicePorts = Arrays.asList(
                new ServicePort(9090, 80, Protocol.TCP),
                new ServicePort(8080, 8080, Protocol.TCP)
        );
        assertServicePorts(config, expServicePorts);
    }

}
