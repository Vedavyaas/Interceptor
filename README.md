# Pheonix Interceptor

> Drop this JAR into any Spring Boot application and every action will automatically publish a rich telemetry event — compute, memory, network, disk, and application metrics — to Kafka. **Zero code changes needed in your app.**

---

## Prerequisites

| Requirement | Version |
|---|---|
| Java | 17+ |
| Spring Boot | 3.x |
| Maven | 3.6+ |

---

## Step 1 — Build & Install the JAR

Run this once inside the **Interceptor project**:

```bash
mvn clean install -DskipTests
```

Two JARs are produced in `target/`:

| File | Purpose |
|---|---|
| `Interceptor-0.0.1-SNAPSHOT.jar` | ✅ Plain library JAR — consumers use this |
| `Interceptor-0.0.1-SNAPSHOT-exec.jar` | Fat runnable JAR (standalone use only) |

`mvn install` places the plain JAR into your local `~/.m2` repository automatically.

---

## Step 2 — Add the Dependency

In the **consumer application's** `pom.xml`:

```xml
<dependency>
    <groupId>com.pheonix</groupId>
    <artifactId>Interceptor</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

> ⚠️ Do **not** add `<classifier>exec</classifier>` — that pulls in the fat JAR which cannot be used as a library.

---

## Step 3 — Add to `application.properties`

In your app's `src/main/resources/application.properties`, add:

```properties
# ── Pheonix Interceptor ───────────────────────────────────────────────────────

# Your company name (appears in every telemetry event)
app.prop.company-name=Your Company Name

# Type of primary resource being monitored
app.prop.resource-type=POSTGRESQL       # e.g. POSTGRESQL, MYSQL, MONGODB, REDIS

# Unique identifier for this resource instance
app.prop.resource-id=db-prod-01

# Deployment environment
app.prop.environment=PRODUCTION         # e.g. PRODUCTION, STAGING, DEVELOPMENT

# Cloud region
app.prop.region=ap-south-1

# Availability zone within the region
app.prop.availability-zone=ap-south-1a
```

> **Kafka is fully managed by the library.** You do not need any `spring.kafka.*` properties. The broker address and all producer settings are hardcoded inside the JAR.

---

## Step 4 — JWT Claims

The interceptor reads one claim from the `Authorization: Bearer <token>` header on every request. Your auth service must include it in the JWT payload:

```json
{
  "agent-id": "agent-01"
}
```

| Claim | Used for |
|---|---|
| `agent-id` | Identifies the calling agent / service instance |

> If the header is absent or the claim is missing, `agentId` defaults to `"unknown"` — the interceptor will still function normally.

---

## Step 5 — Verify

Start your app and consume from the Kafka topic:

```bash
kafka-console-consumer.sh \
  --bootstrap-server <broker>:9092 \
  --topic cloud_metrics \
  --from-beginning
```

**Expected event shape:**

```json
{
  "eventId": "3f4a1b2c-...",
  "company":     { "companyName": "Your Company Name" },
  "agent":       { "agentId": "agent-01", "hostname": "prod-server-01", "ipAddress": "10.0.1.25" },
  "resource":    { "resourceType": "POSTGRESQL", "resourceId": "db-prod-01", "environment": "PRODUCTION", "region": "ap-south-1", "availabilityZone": "ap-south-1a" },
  "compute":     { "cpuUsagePercent": 42.7, "cpuCores": 8, "loadAverage1m": 1.8, "loadAverage5m": 1.4, "loadAverage15m": 1.2 },
  "memory":      { "totalMB": 16384, "usedMB": 9216, "freeMB": 7168, "usagePercent": 56.2 },
  "network":     { "networkInMB": 245.76, "networkOutMB": 512.0, "activeConnections": 67 },
  "disk":        { "diskReadMB": 128.4, "diskWriteMB": 84.2, "diskUsagePercent": 48.3, "storageUsedGB": 380.5 },
  "database":    { "activeConnections": 0, "queriesPerSecond": 0, "averageQueryTimeMs": 0.0, "cacheHitRatio": 0.0, "databaseSizeGB": 0.0 },
  "application": { "requestsPerMinute": 1850, "errorRatePercent": 0.7, "responseTimeMs": 120 }
}
```

---

## What the Library Does Automatically

| Feature | Details |
|---|---|
| **Zero-code activation** | Spring Boot auto-configuration picks it up from the classpath |
| **AOP interception** | Wraps every `@RestController` method transparently |
| **JWT parsing** | Extracts `agent-id` from the `Authorization` header |
| **System metrics** | Live CPU, memory, network I/O, disk I/O via JVM MXBeans |
| **Application metrics** | Rolling requests/min, error rate %, response time per request |
| **Kafka delivery** | Internal isolated producer — `acks=all`, idempotent, snappy compression |
| **Kafka topic** | Fixed: `cloud_metrics` |
| **Non-interference** | Library's `KafkaTemplate` is qualified — will never conflict with your app's own Kafka beans |

---

## Configuration Reference

| Property | Required | Description |
|---|---|---|
| `app.prop.company-name` | ✅ | Company name embedded in every event |
| `app.prop.resource-type` | ✅ | Resource type (e.g. `POSTGRESQL`) |
| `app.prop.resource-id` | ✅ | Unique resource identifier |
| `app.prop.environment` | ✅ | Environment (`PRODUCTION`, `STAGING`, etc.) |
| `app.prop.region` | ✅ | Cloud region (e.g. `ap-south-1`) |
| `app.prop.availability-zone` | ✅ | AZ within the region (e.g. `ap-south-1a`) |

---

## Troubleshooting

| Symptom | Likely Cause | Fix |
|---|---|---|
| No events in Kafka | AOP not activated | Ensure `spring-boot-starter-aop` is not excluded in your app |
| `agent-id` = `"unknown"` | JWT missing claim | Add `agent-id` to your JWT payload |
| Bean conflict on `KafkaTemplate` | Name collision | Library uses `@Qualifier("interceptorKafkaTemplate")` — rename any bean with that exact name in your app |
| `app.prop.*` not binding | Wrong JAR variant | Confirm the plain JAR (not `-exec.jar`) is on the classpath |
