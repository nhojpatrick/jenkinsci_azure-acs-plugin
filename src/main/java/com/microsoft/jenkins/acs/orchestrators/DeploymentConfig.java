package com.microsoft.jenkins.acs.orchestrators;

import hudson.FilePath;

import java.io.IOException;
import java.util.List;

public abstract class DeploymentConfig {

    private final FilePath[] configFiles;

    public DeploymentConfig(FilePath[] configFiles) {
        this.configFiles = configFiles;
    }

    public FilePath[] getConfigFiles() {
        return configFiles;
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
}
