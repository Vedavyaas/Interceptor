package com.pheonix.interceptor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds properties prefixed with "app.prop" from application.properties.
 *
 * Example:
 *   app.prop.company-name=Acme Corp
 *   app.prop.resource-type=POSTGRESQL
 *   app.prop.resource-id=db-prod-01
 *   app.prop.environment=PRODUCTION
 *   app.prop.region=ap-south-1
 *   app.prop.availability-zone=ap-south-1a
 *   app.prop.agent-id=agent-01
 */
@ConfigurationProperties(prefix = "app.prop")
public class InterceptorProperties {

    /** Company name shown in every event payload. */
    private String companyName;

    // ── Resource config ──────────────────────────────────────────────────────
    private String resourceType;
    private String resourceId;
    private String environment;
    private String region;
    private String availabilityZone;

    // ── Getters & Setters ────────────────────────────────────────────────────

    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }

    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }

    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }

    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getAvailabilityZone() { return availabilityZone; }
    public void setAvailabilityZone(String availabilityZone) { this.availabilityZone = availabilityZone; }
}
