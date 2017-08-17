/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs.commands;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.common.annotations.VisibleForTesting;
import com.jcraft.jsch.JSchException;
import com.microsoft.azure.CloudException;
import com.microsoft.azure.management.Azure;
import com.microsoft.azure.management.network.LoadBalancer;
import com.microsoft.azure.management.network.LoadBalancerProbe;
import com.microsoft.azure.management.network.LoadBalancingRule;
import com.microsoft.jenkins.acs.Messages;
import com.microsoft.jenkins.acs.orchestrators.DeploymentConfig;
import com.microsoft.jenkins.acs.util.Constants;
import com.microsoft.jenkins.azurecommons.EnvironmentInjector;
import com.microsoft.jenkins.azurecommons.JobContext;
import com.microsoft.jenkins.azurecommons.command.CommandState;
import com.microsoft.jenkins.azurecommons.command.IBaseCommandData;
import com.microsoft.jenkins.azurecommons.command.ICommand;
import com.microsoft.jenkins.azurecommons.remote.SSHClient;
import com.microsoft.jenkins.kubernetes.KubernetesClientWrapper;
import com.microsoft.jenkins.kubernetes.ResourceUpdateMonitor;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Util;
import hudson.util.VariableResolver;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServicePort;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.docker.commons.credentials.DockerRegistryEndpoint;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Command to deploy Kubernetes configurations to Azure Container Service.
 */
public class KubernetesDeploymentCommand
        implements ICommand<KubernetesDeploymentCommand.IKubernetesDeploymentCommandData> {

    private final ExternalUtils externalUtils;

    public KubernetesDeploymentCommand() {
        this(ExternalUtils.DEFAULT);
    }

    @VisibleForTesting
    KubernetesDeploymentCommand(ExternalUtils externalUtils) {
        this.externalUtils = externalUtils;
    }

    @Override
    public void execute(final IKubernetesDeploymentCommandData context) {
        final JobContext jobContext = context.getJobContext();
        final EnvVars envVars = jobContext.envVars();
        final Azure azure = context.getAzureClient();

        SSHUserPrivateKey sshCredentials = context.getSshCredentials();
        String kubernetesNamespaceCfg = Util.replaceMacro(context.getKubernetesNamespace(), envVars).trim();
        DeploymentConfig deploymentConfig = context.getDeploymentConfig();
        FilePath[] configFiles = deploymentConfig.getConfigFiles();

        FilePath kubeconfigFile = null;
        try {
            SSHClient sshClient = externalUtils
                    .buildSSHClient(context.getMgmtFQDN(), Constants.KUBERNETES_SSH_PORT, sshCredentials)
                    .withLogger(jobContext.logger());
            kubeconfigFile = jobContext.getWorkspace().createTempFile(Constants.KUBECONFIG_PREFIX, "");
            try (SSHClient connected = sshClient.connect();
                 OutputStream out = kubeconfigFile.write()) {
                connected.copyFrom(Constants.KUBECONFIG_FILE, out);
            }

            KubernetesClientWrapper clientWrapper =
                    externalUtils.buildKubernetesClientWrapper(kubeconfigFile.getRemote())
                            .withLogger(jobContext.logger());
            if (context.isEnableConfigSubstitution()) {
                clientWrapper.withVariableResolver(new VariableResolver.ByMap<>(envVars));
            }

            final String clusterName = clusterNameFromConfig(kubeconfigFile.getRemote());
            if (StringUtils.isNotBlank(clusterName)) {
                clientWrapper.withResourceUpdateMonitor(
                        new LoadBalancerServiceUpdateMonitor(context, azure, clusterName));
            }

            final List<DockerRegistryEndpoint> registryCredentials = context.getContainerRegistryCredentials();
            if (!registryCredentials.isEmpty()) {
                final String secretName = KubernetesClientWrapper.prepareSecretName(
                        context.getSecretName(), jobContext.getRun().getDisplayName(), envVars);

                clientWrapper.createOrReplaceSecrets(
                        jobContext.getRun().getParent(),
                        kubernetesNamespaceCfg,
                        secretName,
                        registryCredentials);

                context.logStatus(Messages.KubernetesDeploymentCommand_injectSecretName(
                        Constants.KUBERNETES_SECRET_NAME_PROP, secretName));
                EnvironmentInjector.inject(
                        jobContext.getRun(), Constants.KUBERNETES_SECRET_NAME_PROP, secretName);
            }

            clientWrapper.apply(kubernetesNamespaceCfg, configFiles);

            context.setCommandState(CommandState.Success);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            context.logError(e);
        } catch (Exception e) {
            context.logError(e);
        } finally {
            if (kubeconfigFile != null) {
                context.logStatus(
                        Messages.KubernetesDeploymentCommand_deleteConfigFile(kubeconfigFile.getRemote()));
                try {
                    if (!kubeconfigFile.delete()) {
                        context.logStatus(
                                Messages.KubernetesDeploymentCommand_failedToDeleteFile(kubeconfigFile.getRemote()));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    context.logStatus(
                            Messages.KubernetesDeploymentCommand_failedToDeleteFile(e.getMessage()));
                } catch (Exception e) {
                    context.logStatus(
                            Messages.KubernetesDeploymentCommand_failedToDeleteFile(e.getMessage()));
                }
            }
        }
    }

    @VisibleForTesting
    String clusterNameFromConfig(String kubeconfigFile) throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        try (InputStream in = new FileInputStream(kubeconfigFile)) {
            JsonNode object = mapper.readTree(in);
            try {
                return object.get("current-context").asText();
            } catch (NullPointerException npe) {
                return null;
            }
        }
    }

    @VisibleForTesting
    interface ExternalUtils {
        SSHClient buildSSHClient(String host,
                                 int port,
                                 SSHUserPrivateKey credentials) throws JSchException;

        KubernetesClientWrapper buildKubernetesClientWrapper(String kubeconfigFilePath);

        ExternalUtils DEFAULT = new ExternalUtils() {
            @Override
            public SSHClient buildSSHClient(String host, int port, SSHUserPrivateKey credentials) throws JSchException {
                return new SSHClient(host, port, credentials);
            }

            @Override
            public KubernetesClientWrapper buildKubernetesClientWrapper(String kubeconfigFilePath) {
                return new KubernetesClientWrapper(kubeconfigFilePath);
            }
        };
    }

    /**
     * A monitor that updates the Azure load balancer when a Kubernetes load balancer service gets updated.
     * <p>
     * The service of type {@code LoadBalancer} in Kubernetes is integrated with the load balancers available in
     * the cloud providers like Azure, AWS, etc.
     * <p>
     * When the service is created, Kubernetes will open a port ({@code nodePort}) on the agent for each of
     * the matching deployment's {@code targetPort}s. It then talk to the cloud provider to create the load balancer
     * that routes the traffic from the service port to the nodePort on the target agent, which in turn reaches the
     * targetPort opened by the given container on the target agent. The service port and the nodePort will be shown
     * in the Kubernetes' service UI, while the service port to nodePort routing is defined in the cloud provider's
     * load balancer configuration.
     * <pre><code>
     *     service port --&gt; load balancer --&gt; service nodePort --&gt; service targetPort == containerPort
     * </code></pre>
     * <p>
     * If a service gets updated with the same service port to targetPort configuration, Kubernetes will still create
     * a new service port to nodePort mapping for the updated service, however, the load balancer will not be updated.
     * <p>
     * We check the service update event and update the probes in the matching load balancer if the nodePort is changed.
     */
    static class LoadBalancerServiceUpdateMonitor extends ResourceUpdateMonitor.Adapter {
        private final IKubernetesDeploymentCommandData context;
        private final Azure azure;
        private final String clusterName;

        LoadBalancerServiceUpdateMonitor(IKubernetesDeploymentCommandData context,
                                         Azure azure,
                                         String clusterName) {
            this.context = context;
            this.azure = azure;
            this.clusterName = clusterName;
        }

        @Override
        public boolean isInterestedInService() {
            return true;
        }

        @Override
        public void onServiceUpdate(Service original, Service current) {
            if (original == null) {
                // let the Kubernetes to create new rules
                return;
            }
            if (!"loadbalancer".equalsIgnoreCase(current.getSpec().getType())) {
                return;
            }

            LoadBalancer loadBalancer =
                    azure.loadBalancers().getByResourceGroup(context.getResourceGroupName(), clusterName);
            if (loadBalancer == null) {
                return;
            }
            context.logStatus(Messages.KubernetesDeploymentCommand_tryToUpdateLoadBalancer(loadBalancer.name()));

            String namePrefix = namePrefix(current);
            Map<Integer, LoadBalancingRule> ruleByPort = new HashMap<>();
            for (LoadBalancingRule rule : loadBalancer.loadBalancingRules().values()) {
                if (rule.name().startsWith(namePrefix)) {
                    ruleByPort.put(rule.frontendPort(), rule);
                }
            }

            LoadBalancer.Update loadBalancerUpdate = loadBalancer.update();
            for (ServicePort servicePort : current.getSpec().getPorts()) {
                LoadBalancingRule rule = ruleByPort.get(servicePort.getPort());
                if (rule == null) {
                    // there's new service port in the updated service, Kubernetes will update the rules.
                    return;
                }
                LoadBalancerProbe probe = rule.probe();
                int probePort = probe.port();
                int nodePort = servicePort.getNodePort();
                if (probePort == nodePort) {
                    continue;
                }
                context.logStatus(Messages.KubernetesDeploymentCommand_updateProbe(
                        probe.name(), String.valueOf(probePort), String.valueOf(nodePort)));
                loadBalancerUpdate
                        .updateTcpProbe(probe.name())
                        .withPort(nodePort)
                        .parent();
            }
            try {
                loadBalancerUpdate.apply();
            } catch (CloudException ex) {
                // this may happen when Kubernetes updated the load balancer (possibly by remove the original and
                // create a new one) but we think it didn't and try to update the load balancer at the same time.
                // e.g. "Async operation failed with provisioning state: Canceled: Operation was canceled."
                // If Kubernetes updated the load balancer, we can safely skip our load balancer update failure.
                context.logStatus(ex.getMessage());
            }
        }

        /**
         * See https://github.com/kubernetes/kubernetes/blob/60604f8818aecbc9c37/pkg/cloudprovider/cloud.go#L60 .
         */
        private String namePrefix(Service service) {
            String name = "a" + service.getMetadata().getUid();
            name = name.replaceAll("-", "");
            final int limit = 32;
            if (name.length() > limit) {
                name = name.substring(0, limit);
            }
            return name;
        }
    }

    public interface IKubernetesDeploymentCommandData extends IBaseCommandData {
        Azure getAzureClient();

        String getResourceGroupName();

        String getMgmtFQDN();

        String getLinuxAdminUsername();

        SSHUserPrivateKey getSshCredentials();

        String getKubernetesNamespace();

        DeploymentConfig getDeploymentConfig();

        boolean isEnableConfigSubstitution();

        String getSecretName();

        List<DockerRegistryEndpoint> getContainerRegistryCredentials();
    }
}
