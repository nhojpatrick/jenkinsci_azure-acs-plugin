/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */
package com.microsoft.jenkins.acs;

import java.util.Calendar;
import java.util.UUID;

import com.microsoft.jenkins.acs.services.AzureManagementServiceDelegate;
import com.microsoft.jenkins.acs.util.Constants;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.QueryParameter;

import hudson.model.AbstractProject;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;

public class ACSDeploymentContextDescriptor extends Descriptor<ACSDeploymentContext> {

    public ACSDeploymentContextDescriptor() {

    }

    public String defaultLocation() {
        return "West US";
    }

    public String defaultAgentCount() {
        return "3";
    }

    public FormValidation doCheckAgentCount(@QueryParameter String value) {
        int val = 0;
        try {
            val = Integer.parseInt(value);
            if (val < 1 || val > 40) {
                throw new Exception("Invalid agent count.");
            }
        } catch (Exception ex) {
            return FormValidation.error("An integer value between 0 and 40 is required.");
        }

        return FormValidation.ok();
    }

    public FormValidation doCheckLinuxAdminUsername(@QueryParameter String value) {
        if (value == null || value.length() == 0) {
            return FormValidation.error("Admin Username is required.");
        }

        return FormValidation.ok();
    }

    public FormValidation doCheckSshRSAPublicKey(@QueryParameter String value) {
        if (value == null || value.length() == 0) {
            return FormValidation.error("SSH RSA Public Key is required.");
        }

        return FormValidation.ok();
    }

    public FormValidation doCheckMarathonConfigFile(@QueryParameter String value) {
        if (value == null || value.length() == 0) {
            return FormValidation.error("Marathon config file path is required.");
        }

        return FormValidation.ok();
    }

    public FormValidation doCheckSshKeyFileLocation(@QueryParameter String value) {
        if (value == null || value.length() == 0) {
            return FormValidation.error("SSH RSA private file path is required.");
        }

        return FormValidation.ok();
    }

    public FormValidation doCheckSshKeyFilePassword(@QueryParameter String value) {
        if (value == null || value.length() == 0) {
            return FormValidation.error("SSH RSA private file password is required.");
        }

        return FormValidation.ok();
    }

    public String defaultAgentVMSize() {
        return "Standard_D2";
    }

    public String defaultDnsNamePrefix() {
        String dnsNamePrefix = UUID.randomUUID().toString().replace("-", "");
        long millis = Calendar.getInstance().getTimeInMillis();
        long datePart = millis % 1000000000;
        return "j" + dnsNamePrefix.toLowerCase().substring(0, 8) + datePart;
    }

    public String defaultLinuxAdminname() {
        return "ACSAdmin";
    }

    public ListBoxModel doFillMasterCountItems() {
        ListBoxModel model = new ListBoxModel();
        model.add("1");
        model.add("3");
        model.add("5");
        return model;
    }

    public ListBoxModel doFillLocationItems() {
        ListBoxModel model = new ListBoxModel();
        model.add("West US");
        model.add("East US");
        model.add("North Central US");
        model.add("South Central US");
        model.add("North Europe");
        model.add("West Europe");
        model.add("East Asia");
        model.add("Southeast Asia");
        model.add("Japan East");
        model.add("Japan West");
        model.add("Brazil South");
        return model;
    }

    public ListBoxModel doFillAgentVMSizeItems() {
        ListBoxModel model = new ListBoxModel();
        model.add("Standard_A0");
        model.add("Standard_A1");
        model.add("Standard_A2");
        model.add("Standard_A3");
        model.add("Standard_A4");
        model.add("Standard_A5");
        model.add("Standard_A6");
        model.add("Standard_A7");
        model.add("Standard_A8");
        model.add("Standard_D1");
        model.add("Standard_A9");
        model.add("Standard_A10");
        model.add("Standard_A11");
        model.add("Standard_D2");
        model.add("Standard_D3");
        model.add("Standard_D4");
        model.add("Standard_D11");
        model.add("Standard_D12");
        model.add("Standard_D13");
        model.add("Standard_D14");
        model.add("Standard_D1_v2");
        model.add("Standard_D2_v2");
        model.add("Standard_D3_v2");
        model.add("Standard_D4_v2");
        model.add("Standard_D5_v2");
        model.add("Standard_D11_v2");
        model.add("Standard_D12_v2");
        model.add("Standard_D13_v2");
        model.add("Standard_D14_v2");
        model.add("Standard_G1");
        model.add("Standard_G2");
        model.add("Standard_G3");
        model.add("Standard_G4");
        model.add("Standard_G5");
        model.add("Standard_DS1");
        model.add("Standard_DS2");
        model.add("Standard_DS3");
        model.add("Standard_DS4");
        model.add("Standard_DS11");
        model.add("Standard_DS12");
        model.add("Standard_DS13");
        model.add("Standard_DS14");
        model.add("Standard_GS1");
        model.add("Standard_GS2");
        model.add("Standard_GS3");
        model.add("Standard_GS4");
        model.add("Standard_GS5");
        return model;
    }

    public FormValidation doVerifyConfiguration(@QueryParameter String azureCredentialsId) {
        if (StringUtils.isBlank(azureCredentialsId)) {
            return FormValidation.error("Error: no Azure credentials are selected");
        }

        String response = AzureManagementServiceDelegate.verifyCredentials(azureCredentialsId);

        if (Constants.OP_SUCCESS.equalsIgnoreCase(response)) {
            return FormValidation.ok("Success");
        } else {
            return FormValidation.error(response);
        }
    }

    public boolean isApplicable(Class<? extends AbstractProject> aClass) {
        // Indicates that this builder can be used with all kinds of project types
        return true;
    }

    /**
     * This human readable name is used in the configuration screen.
     */
    public String getDisplayName() {
        return null;
    }
}

