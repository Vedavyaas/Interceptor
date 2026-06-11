package com.pheonix.interceptor.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class Compute {
    private Double cpuUsagePercent;  // null = unavailable (e.g. some JVM/OS combos)
    private int    cpuCores;
    private Double loadAverage1m;    // null = unavailable (e.g. Windows)
    private Double loadAverage5m;
    private Double loadAverage15m;

    public Double getCpuUsagePercent() { return cpuUsagePercent; }
    public void setCpuUsagePercent(Double cpuUsagePercent) { this.cpuUsagePercent = cpuUsagePercent; }

    public int getCpuCores() { return cpuCores; }
    public void setCpuCores(int cpuCores) { this.cpuCores = cpuCores; }

    public Double getLoadAverage1m() { return loadAverage1m; }
    public void setLoadAverage1m(Double loadAverage1m) { this.loadAverage1m = loadAverage1m; }

    public Double getLoadAverage5m() { return loadAverage5m; }
    public void setLoadAverage5m(Double loadAverage5m) { this.loadAverage5m = loadAverage5m; }

    public Double getLoadAverage15m() { return loadAverage15m; }
    public void setLoadAverage15m(Double loadAverage15m) { this.loadAverage15m = loadAverage15m; }
}
