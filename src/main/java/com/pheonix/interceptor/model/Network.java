package com.pheonix.interceptor.model;

public class Network {
    private double networkInMB;
    private double networkOutMB;
    private int activeConnections;

    public double getNetworkInMB() { return networkInMB; }
    public void setNetworkInMB(double networkInMB) { this.networkInMB = networkInMB; }

    public double getNetworkOutMB() { return networkOutMB; }
    public void setNetworkOutMB(double networkOutMB) { this.networkOutMB = networkOutMB; }

    public int getActiveConnections() { return activeConnections; }
    public void setActiveConnections(int activeConnections) { this.activeConnections = activeConnections; }
}
