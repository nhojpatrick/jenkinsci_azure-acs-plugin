package com.microsoft.jenkins.acs.util;

import com.microsoft.jenkins.acs.JobContext;
import com.microsoft.jenkins.acs.Messages;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Util;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.extensions.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.apache.commons.io.IOUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.List;

/**
 * Utility methods to interact with the Kubernetes service using {@link io.fabric8.kubernetes.client.KubernetesClient}.
 */
public final class KubernetesClientUtil {
    public static final String FP_SEPARATOR = ",";

    /**
     * Apply Kubernetes configurations through the given Kubernetes client.
     *
     * @param jobContext      The Jenkins job context
     * @param client          The Kubernetes client that talks to the remote Kubernetes API service
     * @param namespace       The namespace that the components should be created / updated
     * @param configFilePaths The configuration paths in Ant path glob format
     */
    public static void apply(
            final JobContext jobContext,
            final KubernetesClient client,
            final String namespace,
            final String configFilePaths,
            final boolean enableConfigSubstitution) throws IOException, InterruptedException {

        final EnvVars envVars = jobContext.envVars();
        final PrintStream logger = jobContext.logger();

        FilePath workspacePath = jobContext.workspacePath();
        String[] pathPatterns = configFilePaths.split(FP_SEPARATOR);

        for (String pathPattern : pathPatterns) {
            pathPattern = pathPattern.trim();
            if (pathPattern.isEmpty()) {
                continue;
            }
            logger.println(Messages.KubernetesClientUtil_loadingForPath() + pathPattern);
            FilePath[] paths = workspacePath.list(pathPattern);
            if (paths.length <= 0) {
                logger.printf(Messages.KubernetesClientUtil_notFoundInPattern() + pathPattern);
                continue;
            }

            for (FilePath path : paths) {
                URI configURI = path.toURI();

                logger.println(Messages.KubernetesClientUtil_loadingConfiguration() + configURI);

                List<HasMetadata> resources = client
                        .load(prepareConfig(configURI, enableConfigSubstitution, envVars))
                        .get();
                if (resources.isEmpty()) {
                    logger.println(Messages.KubernetesClientUtil_noResourceLoadedFrom() + path);
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
                        logger.println(Messages.KubernetesClientUtil_appliedDeployment() + deployment);
                    } else if (resource instanceof Service) {
                        Service service = (Service) resource;
                        service = client
                                .services()
                                .inNamespace(namespace)
                                .createOrReplace(service);
                        logger.println(Messages.KubernetesClientUtil_appliedService() + service);
                    } else {
                        logger.println(Messages.KubernetesClientUtil_skipped() + resource);
                    }
                }
            }
        }
    }

    private static InputStream prepareConfig(
            final URI configURI,
            final boolean enableConfigSubstitution,
            final EnvVars envVars) throws IOException {
        InputStream stream = configURI.toURL().openStream();
        if (!enableConfigSubstitution) {
            return stream;
        }

        try {
            String content = IOUtils.toString(stream, Charset.forName("UTF-8"));
            if (content != null) {
                content = Util.replaceMacro(content, envVars);
            } else {
                throw new IOException("Cannot parse stream content for " + configURI);
            }
            return new ByteArrayInputStream(content.getBytes());
        } finally {
            stream.close();
        }

    }

    private KubernetesClientUtil() {
        // hide constructor
    }
}
