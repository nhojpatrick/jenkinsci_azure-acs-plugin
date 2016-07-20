# Azure Container Service Plugin


Jenkins Plugin to create an Azure Container Service cluster with a DC/OS orchestrator and deploys a marathon config file to the cluster.

## Pre-requirements
Register and authorize your client application.

Retrieve and use Client ID and Client Secret to be sent to Azure AD during authentication.

Refer to
  * [Adding, Updating, and Removing an Application](https://msdn.microsoft.com/en-us/library/azure/dn132599.aspx) 
  * [Register a client app](https://msdn.microsoft.com/en-us/dn877542.asp)

## How to install the Azure Container Service Plugin
1. Within the Jenkins dashboard, click Manage Jenkins.
2. In the Manage Jenkins page, click Manage Plugins.
3. Click the Available tab.
4. Search for "Azure Container Service Plugin", select the Azure Container Service Plugin.
5. Click either “Install without restart” or “Download now and install after restart”.
6. Restart Jenkins if necessary.

## Configure the plugin
1. Within the Jenkins dashboard, Select a Job then select Configure
2. Scroll to the "Add post-build action" drop down.  
3. Select "Azure Container Service Configuration" 
4. Enter the subscription ID, Client ID, Client Secret and the OAuth 2.0 Token Endpoint in the Azure Profile Configuration section.
5. Enter the Region, DNS Name Prefix, Agent Count, Agent VM Size, Admin Username, Master Count, and SSH RSA Public Key in the Azure Container Service Profile Configuration section.
6. Enter the Marathon config file path, SSH RSA private file path, and SSH RSA private file password in the Marathon Profile Configuration section.
7. Save Job and click on Build now.
8. Jenkins will create an Azure Container Service cluster and deploy the marathon file to the cluster upon cluster creation if cluster doesn't exist.  Otherwise, the marathon file will be deployed to the existing Azure Container Service cluster. 
9. Logs are available in the builds console logs.


 
