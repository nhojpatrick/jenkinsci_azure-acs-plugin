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
import com.microsoft.jenkins.acs.JobContext;
import com.microsoft.jenkins.acs.Messages;
import com.microsoft.jenkins.acs.orchestrators.DeploymentConfig;
import com.microsoft.jenkins.acs.util.Constants;
import com.microsoft.jenkins.acs.util.DeployHelper;
import com.microsoft.jenkins.acs.util.DockerConfigBuilder;
import com.microsoft.jenkins.acs.util.JSchClient;
import com.microsoft.jenkins.acs.util.JsonHelper;
import hudson.EnvVars;
import hudson.FilePath;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.docker.commons.credentials.DockerRegistryEndpoint;

import javax.annotation.Nullable;
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
    MarathonDeploymentCommand(final ExternalUtils externalUtils) {
        this.externalUtils = externalUtils;
    }

    @Override
    public void execute(final IMarathonDeploymentCommandData context) {
        final String host = context.getMgmtFQDN();
        final SSHUserPrivateKey sshCredentials = context.getSshCredentials();
        final String linuxAdminUsername = context.getLinuxAdminUsername();
        final JobContext jobContext = context.jobContext();

        JSchClient client = null;

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

            client = externalUtils.buildJSchClient(
                    host, Constants.DCOS_SSH_PORT, linuxAdminUsername, sshCredentials, context);

            copyCredentialsToAgents(context, jobContext, client);

            for (FilePath configPath : configPaths) {
                String deployedFilename = externalUtils.buildRemoteDeployConfigName();
                context.logStatus(Messages.MarathonDeploymentCommand_copyConfigFileTo(
                        configPath.toURI(), client.getHost(), deployedFilename));

                ByteArrayInputStream in = jobContext.replaceMacro(
                        configPath.read(), context.isEnableConfigSubstitution());

                client.copyTo(in, deployedFilename);
                in.reset();
                String appId = externalUtils.getMarathonAppId(in);
                //ignore if app does not exist
                context.logStatus(Messages.MarathonDeploymentCommand_deletingApp(appId));
                client.execRemote("curl -i -X DELETE http://localhost/marathon/v2/apps/" + appId);
                context.logStatus(Messages.MarathonDeploymentCommand_deployingApp(deployedFilename, appId));
                // NB. about "?force=true"
                // Sometimes the deployment gets rejected after the previous delete of the same application ID
                // with the following message:
                //
                // App is locked by one or more deployments. Override with the option '?force=true'.
                // View details at '/v2/deployments/<DEPLOYMENT_ID>'.
                client.execRemote("curl -i -H 'Content-Type: application/json' -d@'"
                        + deployedFilename + "' http://localhost/marathon/v2/apps?force=true");

                context.logStatus(Messages.MarathonDeploymentCommand_removeTempFile(deployedFilename));

                client.execRemote(String.format("rm -f -- '%s'", deployedFilename));
            }
            context.setDeploymentState(DeploymentState.Success);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            context.logError(e);
        } catch (JSchException | IOException | IllegalArgumentException e) {
            context.logError(e);
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

    private void copyCredentialsToAgents(
            final IMarathonDeploymentCommandData context,
            final JobContext jobContext,
            final JSchClient client) throws IOException, InterruptedException, JSchException {
        final EnvVars envVars = jobContext.envVars();
        final List<DockerRegistryEndpoint> containerRegistryCredentials = context.getContainerRegistryCredentials();
        final String linuxAdminUsername = context.getLinuxAdminUsername();

        if (!containerRegistryCredentials.isEmpty()) {
            DockerConfigBuilder configBuilder = externalUtils.buildDockerConfigBuilder(containerRegistryCredentials);
            FilePath dockercfg = configBuilder.buildArchiveForMarathon(jobContext);
            try {
                String remotePath = prepareCredentialsPath(
                        context.getDcosDockerCredentialsPath(), jobContext, envVars, linuxAdminUsername);

                String dockerArchivePath = remotePath + "/" + Constants.MARATHON_DOCKER_CFG_ARCHIVE;

                List<String> agents = externalUtils.getAgentNodes(client);
                for (String agent : agents) {
                    context.logStatus(Messages.MarathonDeploymentCommand_prepareDockerCredentialsFor(agent));

                    // tunnel through the master SSH connection to connect to the agent SSH service
                    JSchClient forwardClient = client.forwardSSH(agent, Constants.DEFAULT_SSH_PORT);
                    try {
                        // prepare the remote directory structure
                        forwardClient.execRemote("mkdir -p -- '" + escapeSingleQuote(remotePath) + "'");

                        context.logStatus(Messages.MarathonDeploymentCommand_copyDockerCfgTo(
                                dockercfg.getRemote(), forwardClient.getHost(), dockerArchivePath));
                        forwardClient.copyTo(dockercfg.read(), dockerArchivePath);

                        if (context.isDcosDockerCredenditalsPathShared()) {
                            context.logStatus(Messages.MarathonDeploymentCommand_skipAsPathShared());
                        }
                    } finally {
                        forwardClient.close();
                    }
                }

                if (!DeployHelper.checkURIForMarathon(dockerArchivePath)) {
                    context.logStatus(Messages.MarathonDeploymentCommand_uriNotAccepted());
                }
                String archiveUri = "file://" + encodeURIPath(dockerArchivePath);
                // inject the environment variable
                context.logStatus(Messages.MarathonDeploymentCommand_injectEnvironmentVar(
                        Constants.MARATHON_DOCKER_CFG_ARCHIVE_URI, archiveUri));
                DeployHelper.injectEnvironmentVariable(
                        jobContext.getRun(), Constants.MARATHON_DOCKER_CFG_ARCHIVE_URI, archiveUri);
            } finally {
                dockercfg.delete();
            }
        }
    }

    private static List<String> getAgentNodes(final JSchClient client) throws JSchException, IOException {
        final String command = "curl http://leader.mesos:1050/system/health/v1/nodes";
        String output = client.execRemote(command);

        List<String> hosts = getAgentNodes(output);
        if (hosts.isEmpty()) {
            throw new JSchException(Messages.MarathonDeploymentCommand_noAgentFound());
        }

        return hosts;
    }

    private static List<String> getAgentNodes(final String json) {
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
            final String dcosDockerCredentialsPath,
            final JobContext jobContext,
            final EnvVars envVars,
            final String linuxAdminUsername) {
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

    private static String nameForBuild(final JobContext jobContext) {
        String runName = StringUtils.trimToEmpty(
                jobContext.getRun().getParent().getName() + jobContext.getRun().getDisplayName());
        if (StringUtils.isBlank(runName)) {
            runName = UUID.randomUUID().toString();
        }
        return "acs-plugin-dcos-" + runName.replaceAll("[^0-9a-zA-Z]", "-").toLowerCase();
    }

    @VisibleForTesting
    interface ExternalUtils {
        JSchClient buildJSchClient(String host,
                                   int port,
                                   @Nullable String username,
                                   SSHUserPrivateKey credentials,
                                   @Nullable IBaseCommandData context);

        DockerConfigBuilder buildDockerConfigBuilder(List<DockerRegistryEndpoint> endpoints);

        List<String> getAgentNodes(final JSchClient client) throws JSchException, IOException;

        String buildRemoteDeployConfigName();

        String getMarathonAppId(InputStream in) throws IOException;

        ExternalUtils DEFAULT = new ExternalUtils() {
            @Override
            public JSchClient buildJSchClient(
                    final String host,
                    final int port,
                    @Nullable final String username,
                    final SSHUserPrivateKey credentials,
                    @Nullable final IBaseCommandData context) {
                return new JSchClient(host, port, username, credentials, context);
            }

            @Override
            public DockerConfigBuilder buildDockerConfigBuilder(final List<DockerRegistryEndpoint> endpoints) {
                return new DockerConfigBuilder(endpoints);
            }

            @Override
            public List<String> getAgentNodes(final JSchClient client) throws JSchException, IOException {
                return MarathonDeploymentCommand.getAgentNodes(client);
            }

            @Override
            public String buildRemoteDeployConfigName() {
                return DeployHelper.generateRandomDeploymentFileName("json");
            }

            @Override
            public String getMarathonAppId(final InputStream in) throws IOException {
                return JsonHelper.getMarathonAppId(in);
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
