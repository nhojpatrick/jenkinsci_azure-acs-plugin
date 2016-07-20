package org.jenkinsci.plugins.microsoft.commands;

import java.io.IOException;

import org.jenkinsci.plugins.microsoft.exceptions.AzureCloudException;
import org.jenkinsci.plugins.microsoft.util.NetworkResourceProviderHelper;
import org.jenkinsci.plugins.microsoft.commands.DeploymentState;

import com.microsoft.azure.management.network.NetworkResourceProviderClient;
import com.microsoft.windowsazure.exception.ServiceException;

public class GetPublicFQDNCommand implements ICommand<GetPublicFQDNCommand.IGetPublicFQDNCommandData> {
	public void execute(GetPublicFQDNCommand.IGetPublicFQDNCommandData context) {
		try {
	        context.logStatus("Getting management public FQDN.");
			String mgmtFQDN = NetworkResourceProviderHelper.getMgmtPublicIPFQDN(
					context.getNetworkClient(), 
					context.getDnsNamePrefix());
			context.logStatus("Management public FQDN: " + mgmtFQDN);
			context.setMgmtFQDN(mgmtFQDN);
			context.setDeploymentState(DeploymentState.Success);
		} catch (IOException | ServiceException | AzureCloudException e) {
			context.logError("Error deploying marathon service or enabling ports:", e);
		}
    }
	
	public interface IGetPublicFQDNCommandData extends IBaseCommandData {
		public String getDnsNamePrefix();
		public NetworkResourceProviderClient getNetworkClient();
		public void setMgmtFQDN(String mgmtFQDN);
	}
}
