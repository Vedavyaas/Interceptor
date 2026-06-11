package com.pheonix.interceptor.model;

public class Disk {
    private double diskReadMB;
    private double diskWriteMB;
    private double diskUsagePercent;
    private double storageUsedGB;

    public double getDiskReadMB() { return diskReadMB; }
    public void setDiskReadMB(double diskReadMB) { this.diskReadMB = diskReadMB; }

    public double getDiskWriteMB() { return diskWriteMB; }
    public void setDiskWriteMB(double diskWriteMB) { this.diskWriteMB = diskWriteMB; }

    public double getDiskUsagePercent() { return diskUsagePercent; }
    public void setDiskUsagePercent(double diskUsagePercent) { this.diskUsagePercent = diskUsagePercent; }

    public double getStorageUsedGB() { return storageUsedGB; }
    public void setStorageUsedGB(double storageUsedGB) { this.storageUsedGB = storageUsedGB; }
}
