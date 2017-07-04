package com.microsoft.jenkins.acs.orchestrators;

import com.microsoft.azure.management.network.LoadBalancingRule;
import com.microsoft.azure.management.network.Protocol;
import com.microsoft.azure.management.network.TransportProtocol;

public class ServicePort {

    private final int hostPort;
    private final int containerPort;
    private final Protocol protocol;

    public ServicePort(final int hostPort, final int containerPort, final Protocol protocol) {
        this.hostPort = hostPort;
        this.containerPort = containerPort;
        this.protocol = protocol;
    }

    public int getHostPort() {
        return hostPort;
    }

    public int getContainerPort() {
        return containerPort;
    }

    public Protocol getProtocol() {
        return protocol;
    }

    public TransportProtocol getTransportProtocol() {
        if (protocol.equals(Protocol.TCP)) {
            return TransportProtocol.TCP;
        } else if (protocol.equals(Protocol.UDP)) {
            return TransportProtocol.UDP;
        } else {
            return new TransportProtocol(protocol.toString());
        }
    }

    public boolean matchesLoadBalancingRule(final LoadBalancingRule rule) {
        return rule.frontendPort() == hostPort && rule.protocol().equals(getTransportProtocol());
    }

    @Override
    public String toString() {
        return String.format("%d:%d/%s", hostPort, containerPort, protocol);
    }

    @Override
    public boolean equals(final Object obj) {
        if (!(obj instanceof ServicePort)) {
            return false;
        } else if (obj == this) {
            return true;
        } else {
            ServicePort other = (ServicePort) obj;
            return this.hostPort == other.hostPort
                    && this.containerPort == other.containerPort
                    && this.protocol == other.protocol;
        }
    }

    @Override
    public int hashCode() {
        return toString().hashCode();
    }
}
