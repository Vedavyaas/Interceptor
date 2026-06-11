package com.pheonix.interceptor.model;

public class Database {
    private int activeConnections;
    private int queriesPerSecond;
    private double averageQueryTimeMs;
    private double cacheHitRatio;
    private double databaseSizeGB;

    public int getActiveConnections() { return activeConnections; }
    public void setActiveConnections(int activeConnections) { this.activeConnections = activeConnections; }

    public int getQueriesPerSecond() { return queriesPerSecond; }
    public void setQueriesPerSecond(int queriesPerSecond) { this.queriesPerSecond = queriesPerSecond; }

    public double getAverageQueryTimeMs() { return averageQueryTimeMs; }
    public void setAverageQueryTimeMs(double averageQueryTimeMs) { this.averageQueryTimeMs = averageQueryTimeMs; }

    public double getCacheHitRatio() { return cacheHitRatio; }
    public void setCacheHitRatio(double cacheHitRatio) { this.cacheHitRatio = cacheHitRatio; }

    public double getDatabaseSizeGB() { return databaseSizeGB; }
    public void setDatabaseSizeGB(double databaseSizeGB) { this.databaseSizeGB = databaseSizeGB; }
}
