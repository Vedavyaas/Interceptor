package com.pheonix.interceptor.model;

public class Memory {
    private long totalMB;
    private long usedMB;
    private long freeMB;
    private double usagePercent;

    public long getTotalMB() { return totalMB; }
    public void setTotalMB(long totalMB) { this.totalMB = totalMB; }

    public long getUsedMB() { return usedMB; }
    public void setUsedMB(long usedMB) { this.usedMB = usedMB; }

    public long getFreeMB() { return freeMB; }
    public void setFreeMB(long freeMB) { this.freeMB = freeMB; }

    public double getUsagePercent() { return usagePercent; }
    public void setUsagePercent(double usagePercent) { this.usagePercent = usagePercent; }
}
