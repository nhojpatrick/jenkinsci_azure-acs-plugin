package com.microsoft.jenkins.acs.orchestrators;

import com.microsoft.azure.management.network.Protocol;
import com.microsoft.jenkins.acs.util.Constants;
import hudson.FilePath;
import org.apache.commons.io.FileUtils;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

public class MarathonDeploymentConfigTest {
    private void assertInvalidFormatException(String content) throws IOException, InterruptedException {
        final File file = File.createTempFile("tst-acs-", ".yml");
        file.deleteOnExit();
        FileUtils.write(file, content, "UTF-8");

        MarathonDeploymentConfig config = new MarathonDeploymentConfig(new FilePath[]{new FilePath(file)});
        try {
            List<ServicePort> servicePorts = config.getServicePorts();
            Assert.fail("Should throw InvalidFormatException but not");
        } catch (DeploymentConfig.InvalidFormatException e) {
            // Expected
        }
    }


    @Test
    public void getServicePortsInvalidFormat() throws IOException, InterruptedException {
        String content = "";
        assertInvalidFormatException(content);

        content = "{}";
        assertInvalidFormatException(content);

        content = "{\"container\": {}}";
        assertInvalidFormatException(content);

        content = "{\"container\": {\"docker\":{}}}";
        assertInvalidFormatException(content);

        content = "{\"container\": {\"docker\":{\"portMappings\": [{\"containerPort\": 80}]}}}";
        assertInvalidFormatException(content);

        content = "{\"container\": {\"docker\":{\"portMappings\": [{\"hostPort\": 80}]}}}";
        assertInvalidFormatException(content);
    }

    private void assertServicePorts(String[] contents, List<ServicePort> expServicePorts)
            throws IOException, InterruptedException, DeploymentConfig.InvalidFormatException {
        FilePath[] filePaths = new FilePath[contents.length];
        for (int i = 0; i < contents.length; i++) {
            final File file = File.createTempFile("tst-acs-", ".yml");
            file.deleteOnExit();
            FileUtils.write(file, contents[i], "UTF-8");
            filePaths[i] = new FilePath(file);
        }

        MarathonDeploymentConfig config = new MarathonDeploymentConfig(filePaths);
        List<ServicePort> servicePorts = config.getServicePorts();

        Assert.assertEquals(expServicePorts.size(), servicePorts.size());
        for (int i = 0; i < servicePorts.size(); i++) {
            Assert.assertEquals(expServicePorts.get(i), servicePorts.get(i));
        }
    }

    @Test
    public void getServicePorts() throws InterruptedException, DeploymentConfig.InvalidFormatException, IOException {
        String[] contents = new String[]{
                "{\"container\": {\"docker\":{\"portMappings\": [{\"hostPort\": 8080, \"containerPort\": 80}, {\"hostPort\": 8081, \"containerPort\": 8081, \"protocol\": \"udp\"}]}}}",
                "{\"container\": {\"docker\":{\"portMappings\": [{\"hostPort\": 9090, \"containerPort\": 9090}]}}}",
        };
        final List<ServicePort> expServicePorts = Arrays.asList(
                new ServicePort(8080, 80, Protocol.TCP),
                new ServicePort(8081, 8081, Constants.UDP),
                new ServicePort(9090, 9090, Protocol.TCP)
        );
        assertServicePorts(contents, expServicePorts);
    }
}
