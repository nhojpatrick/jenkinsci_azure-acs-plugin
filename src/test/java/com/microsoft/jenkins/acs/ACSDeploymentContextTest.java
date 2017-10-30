package com.microsoft.jenkins.acs;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.google.common.collect.ImmutableList;
import com.microsoft.azure.util.AzureCredentials;
import com.microsoft.jenkins.acs.util.Constants;
import com.microsoft.jenkins.kubernetes.credentials.ResolvedDockerRegistryEndpoint;
import hudson.model.Item;
import hudson.util.FormValidation;
import org.hamcrest.Matchers;
import org.jenkinsci.plugins.docker.commons.credentials.DockerRegistryEndpoint;
import org.jenkinsci.plugins.docker.commons.credentials.DockerRegistryToken;
import org.junit.Test;

import java.net.URL;
import java.util.Arrays;
import java.util.List;

import static com.microsoft.jenkins.acs.ACSDeploymentContext.validate;
import static com.microsoft.jenkins.acs.ACSTestHelper.expectException;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * Tests for the {@link ACSDeploymentContext}.
 */
public class ACSDeploymentContextTest {

    @Test
    public void testContainerServiceNameParsing() {
        assertEquals("test-container", ACSDeploymentContext.getContainerServiceName("test-container"));
        assertEquals("test-container", ACSDeploymentContext.getContainerServiceName("test-container|abc"));
        assertEquals("test-container", ACSDeploymentContext.getContainerServiceName("test-container | abc"));

        expectException(IllegalArgumentException.class, Matchers.is("Container service name is not configured"), new ACSTestHelper.ExceptionRunnable() {
            @Override
            public void run() throws Exception {
                ACSDeploymentContext.getContainerServiceName("  ");
            }
        });
    }

    @Test
    public void testOrchestratorTypeParsing() {
        assertEquals("DCOS", ACSDeploymentContext.getOrchestratorType("test-container|DCOS"));
        assertEquals("Kubernetes", ACSDeploymentContext.getOrchestratorType("test-container | Kubernetes"));

        expectException(IllegalArgumentException.class, Matchers.is("Container service type is not specified"), new ACSTestHelper.ExceptionRunnable() {
            @Override
            public void run() throws Exception {
                ACSDeploymentContext.getOrchestratorType("  ");
            }
        });

        expectException(IllegalArgumentException.class, Matchers.is("Container service type is not specified"), new ACSTestHelper.ExceptionRunnable() {
            @Override
            public void run() throws Exception {
                ACSDeploymentContext.getOrchestratorType("test-container");
            }
        });

        expectException(IllegalArgumentException.class, Matchers.is("Container service type is not specified"), new ACSTestHelper.ExceptionRunnable() {
            @Override
            public void run() throws Exception {
                ACSDeploymentContext.getOrchestratorType("test-container|");
            }
        });
    }

    @Test
    public void testContainerRegistryCredentials() {
        ACSDeploymentContext context = new ACSDeploymentContext(null, null, null, null, null);
        assertListEquals(ImmutableList.of(), context.getContainerRegistryCredentials());

        context.setContainerRegistryCredentials(ImmutableList.of(
                new DockerRegistryEndpoint("", "credentials-1")
        ));
        assertListEquals(ImmutableList.of(new DockerRegistryEndpoint("", "credentials-1")), context.getContainerRegistryCredentials());

        context.setContainerRegistryCredentials(ImmutableList.of(
                new DockerRegistryEndpoint("", "credentials-1"),
                new DockerRegistryEndpoint("acr.azurecr.io", "credentials-2")
        ));
        assertListEquals(
                ImmutableList.of(
                        new DockerRegistryEndpoint("", "credentials-1"),
                        new DockerRegistryEndpoint("http://acr.azurecr.io", "credentials-2")
                ), context.getContainerRegistryCredentials());
    }

    private <T> void assertListEquals(List<? extends T> expected, List<? extends T> actual) {
        assertNotNull(actual);
        assertEquals(expected.toString(), actual.toString());
    }

    @Test
    public void testResolveEndpoints() throws Exception {
        DockerRegistryEndpoint endpoint1 = mock(DockerRegistryEndpoint.class);
        DockerRegistryToken token1 = mock(DockerRegistryToken.class);
        when(endpoint1.getEffectiveUrl()).thenReturn(new URL("https://index.docker.io/v1/"));
        when(endpoint1.getCredentialsId()).thenReturn("cred-1");
        when(endpoint1.getToken(any(Item.class))).thenReturn(token1);

        DockerRegistryEndpoint endpoint2 = mock(DockerRegistryEndpoint.class);
        DockerRegistryToken token2 = mock(DockerRegistryToken.class);
        when(endpoint2.getEffectiveUrl()).thenReturn(new URL("http://acr.azurecr.io"));
        when(endpoint2.getCredentialsId()).thenReturn("cred-2");
        when(endpoint2.getToken(any(Item.class))).thenReturn(token2);

        ACSDeploymentContext context = spy(new ACSDeploymentContext(null, null, null, null, null));
        assertTrue(context.resolvedDockerRegistryEndpoints(mock(Item.class)).isEmpty());

        doReturn(Arrays.asList(endpoint1, endpoint2)).when(context).getContainerRegistryCredentials();
        List<ResolvedDockerRegistryEndpoint> resolved = context.resolvedDockerRegistryEndpoints(mock(Item.class));
        assertEquals(2, resolved.size());
        assertEquals(new URL("https://index.docker.io/v1/"), resolved.get(0).getUrl());
        assertEquals(token1, resolved.get(0).getToken());
        assertEquals(new URL("http://acr.azurecr.io"), resolved.get(1).getUrl());
        assertEquals(token2, resolved.get(1).getToken());
    }

    @Test
    public void testValidate() {
        final String azureCredentialsId = "mock-credentials-id";
        ValidatorBuilder builder = new ValidatorBuilder(azureCredentialsId).withEmptyServicePrincipal();

        assertEquals("ERROR: Azure credentials is not configured or found",
                validate(azureCredentialsId, "", "", "", builder.credentailsFinder));

        builder = new ValidatorBuilder(azureCredentialsId);
        assertEquals("ERROR: Azure resource group is not configured",
                validate(azureCredentialsId, "", "", "", builder.credentailsFinder));
        assertEquals("ERROR: Azure resource group is not configured",
                validate(azureCredentialsId, Constants.INVALID_OPTION, "", "", builder.credentailsFinder));

        assertEquals("ERROR: Azure container service name is not configured",
                validate(azureCredentialsId, "rg", "", "", builder.credentailsFinder));
        assertEquals("ERROR: Azure container service name is not configured",
                validate(azureCredentialsId, "rg", "*", "", builder.credentailsFinder));

        assertEquals("ERROR: Container service type is not specified",
                validate(azureCredentialsId, "rg", "cs", "", builder.credentailsFinder));
        builder = new ValidatorBuilder(azureCredentialsId).withoutSshCredentials();
        assertEquals("ERROR: SSH credentials is not configured",
                validate(azureCredentialsId, "rg", "cs|Custom", "ssh", builder.credentailsFinder));

        builder = new ValidatorBuilder(azureCredentialsId);
        assertEquals("ERROR: Container service type is not specified",
                validate(azureCredentialsId, "rg", "cs", "ssh", builder.credentailsFinder));
        assertEquals("ERROR: Container Service type not-supported is not supported",
                validate(azureCredentialsId, "rg", "cs|not-supported", "ssh", builder.credentailsFinder));
        assertEquals("ERROR: Container Service type Custom is not supported",
                validate(azureCredentialsId, "rg", "cs|Custom", "ssh", builder.credentailsFinder));
    }

    @Test
    public void testDescriptorCheckSecretName() {
        validateSecret(FormValidation.Kind.OK, null, "cs", "");
        validateSecret(FormValidation.Kind.OK, null, "cs", "illegal*");
        validateSecret(FormValidation.Kind.OK, null, "cs|DCOS", "illegal*");

        String longSecret = new String(new char[254]).replace('\0', 'a');
        validateSecret(FormValidation.Kind.ERROR, "ERROR: Secret name is longer than 253 characters.", "cs|Kubernetes", longSecret);
        String longSecretWithVariable = new String(new char[254]).replace('\0', 'a') + "$VAR";
        validateSecret(FormValidation.Kind.ERROR, "ERROR: Secret name is longer than 253 characters.", "cs|Kubernetes", longSecretWithVariable);
        String secretWithVariable = new String(new char[253]).replace('\0', 'a') + "$VAR";
        validateSecret(FormValidation.Kind.OK, null, "cs|Kubernetes", secretWithVariable);

        validateSecret(FormValidation.Kind.OK, null, "cs|Kubernetes", "a");
        validateSecret(FormValidation.Kind.OK, null, "cs|Kubernetes", "$VAR");
        validateSecret(FormValidation.Kind.OK, null, "cs|Kubernetes", "$VAR.$VAR");
        validateSecret(FormValidation.Kind.OK, null, "cs|Kubernetes", "a.b");
        validateSecret(FormValidation.Kind.OK, null, "cs|Kubernetes", "a.b-c.d");

        validateSecret(FormValidation.Kind.ERROR,
                "ERROR: Secret name should consist of lower case alphanumeric characters, &#039;-&#039;, and &#039;.&#039; (pattern ^[a-z0-9]([-a-z0-9]*[a-z0-9])?(\\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*$)",
                "cs|Kubernetes", "a_b");
    }

    @Test
    public void testDescriptorCheckDcosCredentialsPath() {
        validatePath(FormValidation.Kind.OK, null, "", "");

        validatePath(FormValidation.Kind.OK, null, "", "path");
        validatePath(FormValidation.Kind.OK, null, "cs", "path");
        validatePath(FormValidation.Kind.OK, null, "cs|Kubernetes", "path");

        validatePath(FormValidation.Kind.ERROR, "ERROR: Only absolute path is allowed.", "cs|DCOS", "path");
        validatePath(FormValidation.Kind.OK, null, "cs|DCOS", "/path");
        validatePath(FormValidation.Kind.OK, null, "cs|DCOS", "$PREFIX/path");

        validatePath(FormValidation.Kind.OK, null, "cs|DCOS", "$VAR");
        validatePath(FormValidation.Kind.OK, null, "cs|DCOS", "/$VAR/path");

        validatePath(FormValidation.Kind.OK,
                "WARNING: Special characters found in the path (e.g., single quote, backslash, nul, space and "
                        + "other characters that needs URI escaping), which may cause problem for the underlying Marathon "
                        + "resource loading.",
                "cs|DCOS", "/path\\");
        validatePath(FormValidation.Kind.OK,
                "WARNING: Special characters found in the path (e.g., single quote, backslash, nul, space and "
                        + "other characters that needs URI escaping), which may cause problem for the underlying Marathon "
                        + "resource loading.",
                "cs|DCOS", "/path'");
        validatePath(FormValidation.Kind.OK,
                "WARNING: Special characters found in the path (e.g., single quote, backslash, nul, space and "
                        + "other characters that needs URI escaping), which may cause problem for the underlying Marathon "
                        + "resource loading.",
                "cs|DCOS", "/path\0a");

        validatePath(FormValidation.Kind.OK,
                "WARNING: Special characters found in the path (e.g., single quote, backslash, nul, space and "
                        + "other characters that needs URI escaping), which may cause problem for the underlying Marathon "
                        + "resource loading.",
                "cs|DCOS", "/path space");
    }

    private void validateSecret(final FormValidation.Kind kind, final String expect,
                                final String containerService, final String secret) {
        ACSDeploymentContext.DescriptorImpl descriptor = new ACSDeploymentContext.DescriptorImpl();
        FormValidation result = descriptor.doCheckSecretName(containerService, secret);
        assertEquals("Validattion result kind", kind, result.kind);
        assertEquals("Validation message", expect, result.getMessage());
    }

    private void validatePath(final FormValidation.Kind kind, final String expect,
                              final String containerService, final String path) {
        ACSDeploymentContext.DescriptorImpl descriptor = new ACSDeploymentContext.DescriptorImpl();
        FormValidation result = descriptor.doCheckDcosDockerCredentialsPath(containerService, path);
        assertEquals("Validattion result kind", kind, result.kind);
        assertEquals("Validation message", expect, result.getMessage());
    }

    private static class ValidatorBuilder {
        final String azureCredentialsId;
        final ACSDeploymentContext.CredentailsFinder credentailsFinder;
        final AzureCredentials.ServicePrincipal servicePrincipal;
        final SSHUserPrivateKey sshCredentials;

        public ValidatorBuilder(final String azureCredentialsId) {
            this.azureCredentialsId = azureCredentialsId;
            this.credentailsFinder = mock(ACSDeploymentContext.CredentailsFinder.class);
            this.servicePrincipal = mock(AzureCredentials.ServicePrincipal.class);
            this.sshCredentials = mock(SSHUserPrivateKey.class);

            doReturn("mocked-subscription-id").when(this.servicePrincipal).getSubscriptionId();
            doReturn(servicePrincipal).when(this.credentailsFinder).getServicePrincipal(azureCredentialsId);

            doReturn(sshCredentials).when(this.credentailsFinder).getSshCredentials(any(String.class));
        }

        ValidatorBuilder withEmptyServicePrincipal() {
            doReturn("").when(this.servicePrincipal).getSubscriptionId();
            return this;
        }

        ValidatorBuilder withoutSshCredentials() {
            doReturn(null).when(this.credentailsFinder).getSshCredentials(any(String.class));
            return this;
        }
    }
}
