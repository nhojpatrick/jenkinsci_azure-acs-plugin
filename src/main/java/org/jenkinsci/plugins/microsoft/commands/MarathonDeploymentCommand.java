package org.jenkinsci.plugins.microsoft.commands;

import java.io.IOException;
import java.util.Calendar;

import org.jenkinsci.plugins.microsoft.commands.DeploymentState;
import org.jenkinsci.plugins.microsoft.exceptions.AzureCloudException;
import org.jenkinsci.plugins.microsoft.util.JsonHelper;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import com.microsoft.azure.management.network.NetworkResourceProviderClient;
import com.microsoft.azure.management.resources.ResourceManagementClient;

public class MarathonDeploymentCommand implements ICommand<MarathonDeploymentCommand.IMarathonDeploymentCommandData> {
    public void execute(MarathonDeploymentCommand.IMarathonDeploymentCommandData context) {    
    	String host = context.getMgmtFQDN();
    	String marathonConfigFile = context.getMarathonConfigFile();
    	String sshFile = context.getSshKeyFileLocation();
		String filePassword = context.getSshKeyFilePassword();
		String linuxAdminUsername = context.getLinuxAdminUsername(); 
		
		JSch jsch=new JSch();
        Session session = null;
        try {
			java.util.Properties config = new java.util.Properties(); 
			config.put("StrictHostKeyChecking", "no");
			jsch.addIdentity(sshFile, filePassword);
			session=jsch.getSession(linuxAdminUsername, host, 2200);
			session.setConfig(config);
			session.connect();
			
			ChannelSftp channel = null;
			channel = (ChannelSftp)session.openChannel("sftp");
			channel.connect();
			String appId = JsonHelper.getId(marathonConfigFile);
			String deployedFilename = "acsDep" + Calendar.getInstance().getTimeInMillis() + ".json";
	        context.logStatus("Copying marathon file to remote file: " + deployedFilename);
			try {
				channel.put(marathonConfigFile, deployedFilename);
			} catch (SftpException e) {
				context.logError("Error creating remote file:", e);
				return;
			}
			channel.disconnect();
			
			//ignore if app does not exist
			context.logStatus(String.format("Deleting application with appId: '%s' if it exists", appId));
			this.executeCommand(session, "curl -X DELETE localhost:8080/v2/apps/" + appId, context);
			context.logStatus(String.format("Deploying file '%s' with appId to marathon.", deployedFilename, appId));
			this.executeCommand(session, "curl -i -H 'Content-Type: application/json' -d@" + deployedFilename + " localhost:8080/v2/apps", context);
			context.setDeploymentState(DeploymentState.Success);
		} catch (Exception e) {
			context.logError("Error deploying application to marathon:", e);
		}finally {
			if(session != null) {session.disconnect();}
		}   
    }
    
    private void executeCommand(Session session, String command, IBaseCommandData context) 
    		throws IOException, JSchException, AzureCloudException {
    	ChannelExec execChnl = (ChannelExec)session.openChannel("exec");
		execChnl.setCommand(command); 
		
		try {
			 execChnl.connect();
			 try {
		      while(true){
		        if(execChnl.isClosed()){
		        	if(execChnl.getExitStatus() < 0) {
		        		throw new AzureCloudException("Error building or running docker image. Process exected with status: " + 
		        				execChnl.getExitStatus());
		        	}
		          System.out.println("exit-status: "+execChnl.getExitStatus());
		          break;
		        }
		        try{Thread.sleep(1000);}catch(Exception ee){}
		     }
			 }finally {
				 execChnl.disconnect();
			 }
		}catch(AzureCloudException ex) {
			throw ex;
		}
    }
    
    public interface IMarathonDeploymentCommandData extends IBaseCommandData {
    	public String getDnsNamePrefix();
    	public String getLocation();
    	public String getMarathonConfigFile();
    	public String getMgmtFQDN();
    	public String getSshKeyFileLocation();
    	public String getSshKeyFilePassword();
    	public String getLinuxAdminUsername(); 

    	public NetworkResourceProviderClient getNetworkClient();
    	public ResourceManagementClient getResourceClient();
    }
}
