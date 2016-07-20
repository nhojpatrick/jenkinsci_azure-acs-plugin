package org.jenkinsci.plugins.microsoft;

import java.util.Hashtable;

import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.microsoft.commands.ICommand;
import org.jenkinsci.plugins.microsoft.commands.MarathonDeploymentCommand;
import org.jenkinsci.plugins.microsoft.commands.ResourceGroupCommand;
import org.jenkinsci.plugins.microsoft.commands.TemplateDeployCommand;
import org.jenkinsci.plugins.microsoft.commands.TemplateMonitorCommand;
import org.jenkinsci.plugins.microsoft.commands.TransitionInfo;
import org.jenkinsci.plugins.microsoft.commands.ValidateContainerCommand;
import org.jenkinsci.plugins.microsoft.exceptions.AzureCloudException;
import org.jenkinsci.plugins.microsoft.services.AzureManagementServiceDelegate;
import org.jenkinsci.plugins.microsoft.services.IARMTemplateServiceData;
import org.jenkinsci.plugins.microsoft.services.IAzureConnectionData;
import org.jenkinsci.plugins.microsoft.services.ServiceDelegateHelper;
import org.kohsuke.stapler.DataBoundConstructor;
import org.jenkinsci.plugins.microsoft.commands.DeploymentState;
import org.jenkinsci.plugins.microsoft.commands.EnablePortCommand;
import org.jenkinsci.plugins.microsoft.commands.GetPublicFQDNCommand;
import org.jenkinsci.plugins.microsoft.commands.IBaseCommandData;

import com.fasterxml.jackson.databind.JsonNode;
import com.microsoft.azure.management.network.NetworkResourceProviderClient;
import com.microsoft.azure.management.resources.ResourceManagementClient;
import com.microsoft.azure.management.resources.ResourceManagementService;

import hudson.Extension;
import hudson.model.BuildListener;
import hudson.model.Describable;
import hudson.model.Descriptor;
import jenkins.model.Jenkins;

public class ACSDeploymentContext extends AbstractBaseContext
	implements ResourceGroupCommand.IResourceGroupCommandData, 
		ValidateContainerCommand.IValidateContainerCommandData, 
		GetPublicFQDNCommand.IGetPublicFQDNCommandData,
		EnablePortCommand.IEnablePortCommandData, 
		MarathonDeploymentCommand.IMarathonDeploymentCommandData, 
		TemplateDeployCommand.ITemplateDeployCommandData, 
		TemplateMonitorCommand.ITemplateMonitorCommandData, 
		IARMTemplateServiceData, 
		Describable<ACSDeploymentContext> {
	
	private IAzureConnectionData connectData;
	private ResourceManagementClient resourceClient;
	private NetworkResourceProviderClient networkClient;
	private String deploymentName;
	private String mgmtFQDN;
	private String dnsNamePrefix;
	private String agentCount;
	private String agentVMSize;
	private String linuxAdminUsername; 
	private String masterCount;
	private String sshRSAPublicKey;
	private String marathonConfigFile;
	private String sshKeyFilePassword;
	private String sshKeyFileLocation;
    private String location;
    private String orchestratorType;
    
    private static final String EMBEDDED_TEMPLATE_FILENAME = "/templateValue.json";

    public ACSDeploymentContext() {
    	this.location = "West US";
    }
    
    @DataBoundConstructor
	public ACSDeploymentContext(
            final String dnsNamePrefix,
            final String agentCount,
            final String agentVMSize, 
            final String linuxAdminUsername, 
            final String masterCount,
            final String sshRSAPublicKey,
            final String marathonConfigFile,
            final String sshKeyFilePassword,
            final String sshKeyFileLocation,
            final String location) {
	    this.dnsNamePrefix = dnsNamePrefix;
	    this.agentCount = agentCount;
	    this.agentVMSize = agentVMSize; 
	    this.linuxAdminUsername = linuxAdminUsername; 
	    this.orchestratorType = "DCOS";
	    this.masterCount = masterCount;
	    this.sshRSAPublicKey = sshRSAPublicKey;
	    this.marathonConfigFile = marathonConfigFile;
	    this.sshKeyFilePassword = sshKeyFilePassword;
        this.sshKeyFileLocation = sshKeyFileLocation;
        this.location = location;
	}
	
    @SuppressWarnings("unchecked")
	@Override
    public Descriptor<ACSDeploymentContext>  getDescriptor() {
    	return Jenkins.getInstance().getDescriptor(getClass());
    }

	public String getDnsNamePrefix() {
		return this.dnsNamePrefix;
	}
	
	public String getAgentCount() {
		return this.agentCount;
	}
    
	public String getAgentVMSize() { 
    	return this.agentVMSize; 
    }
    
    public String getLinuxAdminUsername() { 
    	return this.linuxAdminUsername; 
    }
    
    public String getOrchestratorType() {
    	return this.orchestratorType;
    }
    
    public String getMasterCount() {
    	return this.masterCount;
    }
    
    public String getSshRSAPublicKey() { 
    	return this.sshRSAPublicKey; 
    }
    
    public String getMarathonConfigFile() { 
    	return this.marathonConfigFile; 
    }

    public String getSshKeyFileLocation() { 
    	return this.sshKeyFileLocation; 
    }

    public String getSshKeyFilePassword() { 
    	return this.sshKeyFilePassword; 
    }

    public String getLocation() {
    	return this.location;
    }    
	
	public void setDeploymentName(String deploymentName) {
		this.deploymentName = deploymentName;
	}
	
	public void setMgmtFQDN(String mgmtFQDN) {
		this.mgmtFQDN = mgmtFQDN;
	}

	public String getResourceGroupName() {
		return this.dnsNamePrefix;
	}

	public String getDeploymentName() {
		return this.deploymentName;
	}
	
	public String getMgmtFQDN() {
		return this.mgmtFQDN;
	}
	
	@Override
	public IBaseCommandData getDataForCommand(ICommand command) {
		return this;
	}

	public ResourceManagementClient getResourceClient() {
		return this.resourceClient;
	}
	
	public NetworkResourceProviderClient getNetworkClient() {
		return this.networkClient;
	}
	
	public void configure(BuildListener listener, IAzureConnectionData connectData) throws AzureCloudException {
		this.connectData = connectData;
		this.resourceClient = ResourceManagementService.create(
				org.jenkinsci.plugins.microsoft.services.ServiceDelegateHelper.load(connectData));
		this.networkClient = ServiceDelegateHelper.getNetworkManagementClient(
				org.jenkinsci.plugins.microsoft.services.ServiceDelegateHelper.load(connectData));
		
		Hashtable<Class, TransitionInfo> commands = new Hashtable<Class, TransitionInfo>();
		commands.put(ResourceGroupCommand.class, new TransitionInfo(new ResourceGroupCommand(), ValidateContainerCommand.class, null));		
		commands.put(ValidateContainerCommand.class, new TransitionInfo(new ValidateContainerCommand(), GetPublicFQDNCommand.class, TemplateDeployCommand.class));
		commands.put(TemplateDeployCommand.class, new TransitionInfo(new TemplateDeployCommand(), TemplateMonitorCommand.class, null));
		commands.put(TemplateMonitorCommand.class, new TransitionInfo(new TemplateMonitorCommand(), GetPublicFQDNCommand.class, null));
		commands.put(GetPublicFQDNCommand.class, new TransitionInfo(new GetPublicFQDNCommand(), MarathonDeploymentCommand.class, null));
		commands.put(MarathonDeploymentCommand.class, new TransitionInfo(new MarathonDeploymentCommand(), EnablePortCommand.class, null));
		commands.put(EnablePortCommand.class, new TransitionInfo(new EnablePortCommand(), null, null));
		super.configure(listener, commands, ResourceGroupCommand.class);
		this.setDeploymentState(DeploymentState.Running);
	}

	@Override
	public String getEmbeddedTemplateName() {
		return EMBEDDED_TEMPLATE_FILENAME;
	}

	@Override
	public void configureTemplate(JsonNode tmp) throws IllegalAccessException, AzureCloudException {
        if (StringUtils.isBlank(this.getDnsNamePrefix())) {
            throw new AzureCloudException(
                    String.format("Invalid DNS name prefix '%s'", this.dnsNamePrefix));
        }

        AzureManagementServiceDelegate.validateAndAddFieldValue("string", this.dnsNamePrefix, "dnsNamePrefix",
        		String.format("Invalid DNS name prefix '%s'", this.getDnsNamePrefix()),
        		tmp);
        AzureManagementServiceDelegate.validateAndAddFieldValue("int", this.agentCount, "agentCount", null, tmp);
        AzureManagementServiceDelegate.validateAndAddFieldValue("string", this.agentVMSize, "agentVMSize", null, tmp);
        AzureManagementServiceDelegate.validateAndAddFieldValue("string", this.linuxAdminUsername, "linuxAdminUsername", null, tmp);
        AzureManagementServiceDelegate.validateAndAddFieldValue("string", this.orchestratorType, "orchestratorType", null, tmp);
        AzureManagementServiceDelegate.validateAndAddFieldValue("int", this.masterCount, "masterCount", null, tmp);
        AzureManagementServiceDelegate.validateAndAddFieldValue("string", this.sshRSAPublicKey, "sshRSAPublicKey", null, tmp);
    }

	@Override
	public IARMTemplateServiceData getArmTemplateServiceData() {
		return this;
	}

	@Override
	public IAzureConnectionData getAzureConnectionData() {
		return this.connectData;
	}
	
    @Extension
    public static final class DescriptorImpl extends ACSDeploymentContextDescriptor {
    }
}
