/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs.util;

import com.microsoft.jenkins.acs.JobContext;
import com.microsoft.jenkins.acs.Messages;
import hudson.FilePath;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.extensions.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.jenkinsci.plugins.docker.commons.credentials.DockerRegistryEndpoint;

import java.io.IOException;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility methods to interact with the Kubernetes service using {@link io.fabric8.kubernetes.client.KubernetesClient}.
 */
public final class KubernetesClientUtil {
    /**
     * Apply Kubernetes configurations through the given Kubernetes client.
     *
     * @param jobContext               The Jenkins job context
     * @param client                   The Kubernetes client that talks to the remote Kubernetes API service
     * @param namespace                The namespace that the components should be created / updated
     * @param configFiles              The configuration files to be deployed
     * @param enableConfigSubstitution Is environment variable substitution in config enabled?
     */
    public static void apply(
            final JobContext jobContext,
            final KubernetesClient client,
            final String namespace,
            final FilePath[] configFiles,
            final boolean enableConfigSubstitution) throws IOException, InterruptedException {

        final PrintStream logger = jobContext.logger();

        for (FilePath path : configFiles) {
            logger.println(Messages.KubernetesClientUtil_loadingConfiguration(path));

            List<HasMetadata> resources = client
                    .load(jobContext.replaceMacro(path.read(), enableConfigSubstitution))
                    .get();
            if (resources.isEmpty()) {
                logger.println(Messages.KubernetesClientUtil_noResourceLoadedFrom(path));
                continue;
            }
            for (HasMetadata resource : resources) {
                if (resource instanceof Deployment) {
                    Deployment deployment = (Deployment) resource;
                    deployment = client
                            .extensions()
                            .deployments()
                            .inNamespace(namespace)
                            .createOrReplace(deployment);
                    logger.println(Messages.KubernetesClientUtil_appliedDeployment(deployment));
                } else if (resource instanceof Service) {
                    Service service = (Service) resource;
                    service = client
                            .services()
                            .inNamespace(namespace)
                            .createOrReplace(service);
                    logger.println(Messages.KubernetesClientUtil_appliedService(service));
                } else {
                    logger.println(Messages.KubernetesClientUtil_skipped(resource));
                }
            }
        }
    }

    /**
     * Construct the dockercfg with all the provided credentials, and create a new Secret resource for the Kubernetes
     * cluster.
     * <p>
     * This can be used by the Pods later to pull images from the private container registry.
     *
     * @param jobContext          the current job context
     * @param kubernetesClient    The Kubernetes client that talks to the remote Kubernetes API service
     * @param kubernetesNamespace The namespace that the Secret should be created / updated
     * @param secretName          The name of the Secret
     * @param credentials         All the configured credentials
     * @see <a href="https://kubernetes.io/docs/tasks/configure-pod-container/pull-image-private-registry">
     * Pull an Image from a Private Registry
     * </a>
     */
    public static void prepareSecrets(
            final JobContext jobContext,
            final KubernetesClient kubernetesClient,
            final String kubernetesNamespace,
            final String secretName,
            final List<DockerRegistryEndpoint> credentials) throws IOException {
        final PrintStream logger = jobContext.logger();

        logger.println(Messages.KubernetesClientUtil_prepareSecretsWithName(secretName));

        DockerConfigBuilder dockerConfigBuilder = new DockerConfigBuilder(credentials);
        String dockercfg = dockerConfigBuilder.buildDockercfgForKubernetes(jobContext);

        Map<String, String> data = new HashMap<>();
        data.put(".dockercfg", dockercfg);
        Secret secret = new SecretBuilder()
                .withNewMetadata()
                .withName(secretName)
                .withNamespace(kubernetesNamespace)
                .endMetadata()
                .withData(data)
                .withType("kubernetes.io/dockercfg")
                .build();

        kubernetesClient.secrets().inNamespace(kubernetesNamespace).createOrReplace(secret);
    }

    private KubernetesClientUtil() {
        // hide constructor
    }
}
