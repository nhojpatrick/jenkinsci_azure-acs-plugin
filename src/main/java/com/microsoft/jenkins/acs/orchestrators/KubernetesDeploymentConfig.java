package com.microsoft.jenkins.acs.orchestrators;

import hudson.FilePath;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

public class KubernetesDeploymentConfig extends DeploymentConfig {

    public KubernetesDeploymentConfig(FilePath[] configFiles) {
        super(configFiles);
    }

    @Override
    public String getResourcePrefix() {
        return "k8s";
    }

    @Override
    public List<ServicePort> getServicePorts() throws IOException, InvalidFormatException, InterruptedException {
        // ACS with Kubernetes will add a security rule for the service port automatically,
        // so here we just return an empty list
        return Arrays.asList();
    }
}
