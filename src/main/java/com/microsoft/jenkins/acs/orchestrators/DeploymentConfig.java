package com.microsoft.jenkins.acs.orchestrators;

import com.microsoft.azure.management.containerservice.ContainerServiceOrchestratorTypes;
import com.microsoft.jenkins.acs.Messages;
import hudson.EnvVars;
import hudson.FilePath;

import java.io.IOException;
import java.io.Serializable;
import java.util.Arrays;
import java.util.List;

import static com.microsoft.azure.management.containerservice.ContainerServiceOrchestratorTypes.DCOS;
import static com.microsoft.azure.management.containerservice.ContainerServiceOrchestratorTypes.KUBERNETES;
import static com.microsoft.azure.management.containerservice.ContainerServiceOrchestratorTypes.SWARM;

public abstract class DeploymentConfig implements Serializable {

    private FilePath[] configFiles;

    public DeploymentConfig(FilePath[] configFiles) {
        this.configFiles = Arrays.stream(configFiles).toArray(FilePath[]::new);
    }

    public FilePath[] getConfigFiles() {
        return Arrays.stream(configFiles).toArray(FilePath[]::new);
    }

    public abstract String getResourcePrefix();

    public abstract List<ServicePort> getServicePorts()
            throws IOException, InvalidFormatException, InterruptedException;

    public static final class InvalidFormatException extends Exception {

        public InvalidFormatException(String msg) {
            super(msg);
        }

        public InvalidFormatException(Exception ex) {
            super(ex);
        }

    }

    public static class Factory implements Serializable {
        private static final long serialVersionUID = 1L;

        private final String configFilePaths;

        public Factory(String configFilePaths) {
            this.configFilePaths = configFilePaths;
        }

        public DeploymentConfig build(ContainerServiceOrchestratorTypes type,
                                      FilePath workspace,
                                      EnvVars envVars) throws IOException, InterruptedException {
            final String expandedConfigFilePaths = envVars.expand(configFilePaths);
            final FilePath[] configFiles = workspace.list(expandedConfigFilePaths);
            if (configFiles.length == 0) {
                throw new IllegalArgumentException(Messages.ACSDeploymentContext_noConfigFilesFound(configFilePaths));
            }

            if (DCOS.equals(type)) {
                return new MarathonDeploymentConfig(configFiles);
            } else if (KUBERNETES.equals(type)) {
                return new KubernetesDeploymentConfig(configFiles);
            } else if (SWARM.equals(type)) {
                return new SwarmDeploymentConfig(configFiles);
            } else {
                throw new IllegalArgumentException(
                        Messages.ACSDeploymentContext_orchestratorNotSupported(type));
            }
        }

        public DeploymentConfig buildForAKS(
                FilePath workspace, EnvVars envVars) throws IOException, InterruptedException {
            final String expandedConfigFilePaths = envVars.expand(configFilePaths);
            final FilePath[] configFiles = workspace.list(expandedConfigFilePaths);
            if (configFiles.length == 0) {
                throw new IllegalArgumentException(Messages.ACSDeploymentContext_noConfigFilesFound(configFilePaths));
            }
            return new KubernetesDeploymentConfig(configFiles);
        }
    }
}
