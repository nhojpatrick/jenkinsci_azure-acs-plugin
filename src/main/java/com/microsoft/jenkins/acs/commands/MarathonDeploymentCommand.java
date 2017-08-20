/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs.commands;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.google.common.annotations.VisibleForTesting;
import com.jcraft.jsch.JSchException;
import com.microsoft.jenkins.acs.Messages;
import com.microsoft.jenkins.acs.orchestrators.DeploymentConfig;
import com.microsoft.jenkins.acs.util.Constants;
import com.microsoft.jenkins.acs.util.DeployHelper;
import com.microsoft.jenkins.acs.util.JsonHelper;
import com.microsoft.jenkins.azurecommons.EnvironmentInjector;
import com.microsoft.jenkins.azurecommons.JobContext;
import com.microsoft.jenkins.azurecommons.command.CommandState;
import com.microsoft.jenkins.azurecommons.command.IBaseCommandData;
import com.microsoft.jenkins.azurecommons.command.ICommand;
import com.microsoft.jenkins.azurecommons.remote.SSHClient;
import com.microsoft.jenkins.kubernetes.util.DockerConfigBuilder;
import hudson.EnvVars;
import hudson.FilePath;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.docker.commons.credentials.DockerRegistryEndpoint;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.microsoft.jenkins.acs.util.DeployHelper.encodeURIPath;
import static com.microsoft.jenkins.acs.util.DeployHelper.escapeSingleQuote;

public class MarathonDeploymentCommand implements ICommand<MarathonDeploymentCommand.IMarathonDeploymentCommandData> {
    private final ExternalUtils externalUtils;

    public MarathonDeploymentCommand() {
        this(ExternalUtils.DEFAULT);
    }

    @VisibleForTesting
    MarathonDeploymentCommand(ExternalUtils externalUtils) {
        this.externalUtils = externalUtils;
    }

    @Override
    public void execute(IMarathonDeploymentCommandData context) {
        final String host = context.getMgmtFQDN();
        final SSHUserPrivateKey sshCredentials = context.getSshCredentials();
        final JobContext jobContext = context.getJobContext();

        try {
            final DeploymentConfig config = context.getDeploymentConfig();
            if (config == null) {
                context.logError(Messages.DeploymentConfig_invalidConfig());
                return;
            }

            FilePath[] configPaths = config.getConfigFiles();
            if (configPaths == null || configPaths.length == 0) {
                context.logError(Messages.MarathonDeploymentCommand_configNotFound());
                return;
            }

            SSHClient client =
                    externalUtils.buildSSHClient(host, Constants.DCOS_SSH_PORT, sshCredentials)
                            .withLogger(jobContext.logger());

            try (SSHClient connected = client.connect()) {
                copyCredentialsToAgents(context, jobContext, connected);

                EnvVars envVars = context.getEnvVars();

                for (FilePath configPath : configPaths) {
                    String deployedFilename = externalUtils.buildRemoteDeployConfigName();
                    context.logStatus(Messages.MarathonDeploymentCommand_copyConfigFileTo(
                            configPath.toURI(), connected.getHost(), deployedFilename));

                    ByteArrayInputStream in = externalUtils.replaceMacro(
                            configPath.read(), envVars, context.isEnableConfigSubstitution());

                    connected.copyTo(in, deployedFilename);
                    in.reset();
                    String appId = externalUtils.getMarathonAppId(in);
                    //ignore if app does not exist
                    context.logStatus(Messages.MarathonDeploymentCommand_deletingApp(appId));
                    connected.execRemote("curl -i -X DELETE http://localhost/marathon/v2/apps/" + appId);
                    context.logStatus(Messages.MarathonDeploymentCommand_deployingApp(deployedFilename, appId));
                    // NB. about "?force=true"
                    // Sometimes the deployment gets rejected after the previous delete of the same application ID
                    // with the following message:
                    //
                    // App is locked by one or more deployments. Override with the option '?force=true'.
                    // View details at '/v2/deployments/<DEPLOYMENT_ID>'.
                    connected.execRemote("curl -i -H 'Content-Type: application/json' -d@'"
                            + deployedFilename + "' http://localhost/marathon/v2/apps?force=true");

                    context.logStatus(Messages.MarathonDeploymentCommand_removeTempFile(deployedFilename));

                    connected.execRemote(String.format("rm -f -- '%s'", deployedFilename));
                }
            }
            context.setCommandState(CommandState.Success);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            context.logError(e);
        } catch (Exception e) {
            context.logError(e);
        }
    }

    private void copyCredentialsToAgents(
            IMarathonDeploymentCommandData context,
            JobContext jobContext,
            SSHClient client
    ) throws Exception {
        final EnvVars envVars = context.getEnvVars();
        final List<DockerRegistryEndpoint> containerRegistryCredentials = context.getContainerRegistryCredentials();
        final String linuxAdminUsername = context.getLinuxAdminUsername();

        if (!containerRegistryCredentials.isEmpty()) {
            DockerConfigBuilder configBuilder = externalUtils.buildDockerConfigBuilder(containerRegistryCredentials);
            FilePath dockercfg = configBuilder.buildArchive(jobContext.getWorkspace(), jobContext.getRun().getParent());
            try {
                String remotePath = prepareCredentialsPath(
                        context.getDcosDockerCredentialsPath(), jobContext, envVars, linuxAdminUsername);

                String dockerArchivePath = remotePath + "/" + Constants.MARATHON_DOCKER_CFG_ARCHIVE;

                List<String> agents = externalUtils.getAgentNodes(client);
                for (String agent : agents) {
                    context.logStatus(Messages.MarathonDeploymentCommand_prepareDockerCredentialsFor(agent));

                    // tunnel through the master SSH connection to connect to the agent SSH service
                    SSHClient forwardClient =
                            client.forwardSSH(agent, Constants.DEFAULT_SSH_PORT).withLogger(jobContext.logger());
                    try (SSHClient connected = forwardClient.connect()) {
                        // prepare the remote directory structure
                        connected.execRemote("mkdir -p -- '" + escapeSingleQuote(remotePath) + "'");

                        context.logStatus(Messages.MarathonDeploymentCommand_copyDockerCfgTo(
                                dockercfg.getRemote(), agent, dockerArchivePath));
                        connected.copyTo(dockercfg.read(), dockerArchivePath);

                        if (context.isDcosDockerCredenditalsPathShared()) {
                            context.logStatus(Messages.MarathonDeploymentCommand_skipAsPathShared());
                        }
                    }
                }

                if (!DeployHelper.checkURIForMarathon(dockerArchivePath)) {
                    context.logStatus(Messages.MarathonDeploymentCommand_uriNotAccepted());
                }
                String archiveUri = "file://" + encodeURIPath(dockerArchivePath);
                // inject the environment variable
                context.logStatus(Messages.MarathonDeploymentCommand_injectEnvironmentVar(
                        Constants.MARATHON_DOCKER_CFG_ARCHIVE_URI, archiveUri));
                EnvironmentInjector.inject(
                        jobContext.getRun(), envVars, Constants.MARATHON_DOCKER_CFG_ARCHIVE_URI, archiveUri);
            } finally {
                dockercfg.delete();
            }
        }
    }

    private static List<String> getAgentNodes(
            SSHClient client) throws Exception {
        final String command = "curl http://leader.mesos:1050/system/health/v1/nodes";
        String output = client.execRemote(command);

        List<String> hosts = getAgentNodes(output);
        if (hosts.isEmpty()) {
            throw new JSchException(Messages.MarathonDeploymentCommand_noAgentFound());
        }

        return hosts;
    }

    private static List<String> getAgentNodes(String json) {
        // sample input
        // {
        //     "nodes": [
        //         {"host_ip": "10.32.0.5",  "health": 0, "role": "agent"},
        //         {"host_ip": "10.0.0.4",   "health": 0, "role": "agent_public"},
        //         {"host_ip": "10.32.0.4",  "health": 0, "role": "agent"},
        //         {"host_ip": "172.16.0.5", "health": 0, "role": "master"},
        //         {"host_ip": "10.32.0.6",  "health": 0, "role": "agent"}
        //     ]
        // }
        final ObjectMapper mapper = new ObjectMapper();
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot parse JSON object from: " + json, e);
        }
        List<String> agentNodes = new ArrayList<>();
        JsonNode nodes = root.get("nodes");
        if (nodes == null || nodes.getNodeType() != JsonNodeType.ARRAY) {
            return agentNodes;
        }
        ArrayNode nodesArray = (ArrayNode) nodes;
        for (JsonNode node : nodesArray) {
            if (!"master".equals(node.get("role").textValue())) {
                agentNodes.add(node.get("host_ip").textValue());
            }
        }

        return agentNodes;
    }

    private static String prepareCredentialsPath(
            String dcosDockerCredentialsPath,
            JobContext jobContext,
            EnvVars envVars,
            String linuxAdminUsername) {
        String name = StringUtils.trimToEmpty(envVars.expand(dcosDockerCredentialsPath));
        if (StringUtils.isNotBlank(name)) {
            if (name.charAt(0) != '/') {
                throw new IllegalArgumentException(
                        Messages.MarathonDeploymentCommand_relativePathNotAllowed(name));
            }
            name = name.replaceAll("/+$", "");
            if (name.isEmpty()) {
                name = "/";
            }
            return name;
        }
        return "/home/" + linuxAdminUsername + "/acs-plugin-dcos.docker/"
                + nameForBuild(jobContext);
    }

    private static String nameForBuild(JobContext jobContext) {
        String runName = StringUtils.trimToEmpty(
                jobContext.getRun().getParent().getName() + jobContext.getRun().getDisplayName());
        if (StringUtils.isBlank(runName)) {
            runName = UUID.randomUUID().toString();
        }
        return "acs-plugin-dcos-" + runName.replaceAll("[^0-9a-zA-Z]", "-").toLowerCase();
    }

    @VisibleForTesting
    interface ExternalUtils {
        SSHClient buildSSHClient(String host,
                                 int port,
                                 SSHUserPrivateKey credentials) throws JSchException;

        DockerConfigBuilder buildDockerConfigBuilder(List<DockerRegistryEndpoint> endpoints);

        List<String> getAgentNodes(SSHClient client) throws Exception;

        String buildRemoteDeployConfigName();

        String getMarathonAppId(InputStream in) throws IOException;

        ByteArrayInputStream replaceMacro(InputStream original, EnvVars envVars, boolean enabled) throws IOException;

        ExternalUtils DEFAULT = new ExternalUtils() {
            @Override
            public SSHClient buildSSHClient(
                    String host,
                    int port,
                    SSHUserPrivateKey credentials) throws JSchException {
                return new SSHClient(host, port, credentials);
            }

            @Override
            public DockerConfigBuilder buildDockerConfigBuilder(List<DockerRegistryEndpoint> endpoints) {
                return new DockerConfigBuilder(endpoints);
            }

            @Override
            public List<String> getAgentNodes(SSHClient client) throws Exception {
                return MarathonDeploymentCommand.getAgentNodes(client);
            }

            @Override
            public String buildRemoteDeployConfigName() {
                return DeployHelper.generateRandomDeploymentFileName("json");
            }

            @Override
            public String getMarathonAppId(InputStream in) throws IOException {
                return JsonHelper.getMarathonAppId(in);
            }

            @Override
            public ByteArrayInputStream replaceMacro(
                    InputStream original, EnvVars envVars, boolean enabled) throws IOException {
                return DeployHelper.replaceMacro(original, envVars, enabled);
            }
        };
    }

    public interface IMarathonDeploymentCommandData extends IBaseCommandData {
        String getMgmtFQDN();

        String getLinuxAdminUsername();

        SSHUserPrivateKey getSshCredentials();

        DeploymentConfig getDeploymentConfig() throws IOException, InterruptedException;

        boolean isEnableConfigSubstitution();

        String getDcosDockerCredentialsPath();

        boolean isDcosDockerCredenditalsPathShared();

        List<DockerRegistryEndpoint> getContainerRegistryCredentials();
    }
}
