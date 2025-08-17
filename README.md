# Tesla-Automatic-Preconditioning

A Spring Framework 6 / Spring Boot 3 service that reads your calendar, predicts commute timing with Google Maps Routes, checks recent weather via Google Weather, and automatically manages **one-time preconditioning schedules** on your Tesla (via your Fleet/Tessie integration). It persists schedule links in PostgreSQL and uses Liquibase for schema management.

## Highlights

- **Calendar ingest**: Google Calendar (service account + optional domain-wide delegation/impersonation).
- **Route ETA**: Google Maps Routes API with field masks for minimal response size.
- **Weather-aware**: Google Weather API (current, forecast, **hourly history**). Built-in heuristic for **snow/ice accumulation risk** over the last 12h (recency- and temperature-weighted).
- **Tesla scheduling**: Adds one-time preconditioning entries; then **asynchronously verifies** creation after a configurable delay using a **new DB transaction**.
- **PostgreSQL + Liquibase**: Reliable persistence and versioned schema.
- **Testcontainers** in tests; Java 24 toolchain.

## Architecture

- Google Calendar  ->  Event window & attendees
- Google Routes    ->  ETA / duration (field-masked)
- Google Weather   ->  Current + Forecast + Hourly History (prev 24h)
- Service Logic    ->  preconditioning window, buffer, weather risk
- Tesla Fleet/Tessie -> add one-time preconditioning schedule
- PostgreSQL       ->  CalendarPreConditionLinkEntity (schedule linkage)

## Requirements

- Java 24 (JDK 24)
- Maven 3.9+
- PostgreSQL 14+
- Google Cloud project with:
    - **Calendar API**, **Routes API**, **Weather API** enabled
    - Service account credentials (JSON)
- Teslemetry API token

## Quick Start

```bash
# build
./mvnw clean package

# run
java -jar target/Tesla-Automatic-Preconditioning-0.0.39.jar
```

## ⚠️ Security & Configuration

### **IMPORTANT: Protect Your Credentials**

This application requires sensitive credentials. **NEVER commit these to version control!**

#### Protected Files (Already in .gitignore)
- `src/main/resources/google/*.json` - Google service account credentials
- `src/main/resources/application.yml` - Your actual configuration with secrets
- `.env` files - Environment-specific configurations
- `*.pem`, `*.key`, `*.p12` - Any private keys or certificates

#### Setup Instructions

1. **Copy the template configuration:**
   ```bash
   cp application.yml.template src/main/resources/application.yml
   ```

2. **Edit `src/main/resources/application.yml` with your actual values:**
   - Google API credentials
   - Teslemetry token
   - Email/SMTP credentials
   - Tesla VINs
   - Home coordinates
   - SMS notification recipients

3. **Store Google service account JSON:**
   - Place your service account JSON file in `src/main/resources/google/`
   - Update `google.calendar.credentials-path` in application.yml to point to it
   - This file is automatically ignored by git

4. **Never commit secrets:**
   - The `.gitignore` is configured to exclude all credential files
   - Always use the template file (`application.yml.template`) as a reference
   - For production, consider using environment variables or a secrets manager

#### Environment Variables (Recommended for Production)

Instead of storing secrets in `application.yml`, use environment variables:

```bash
export GOOGLE_WEATHER_API_KEY="your-api-key"
export TESLEMETRY_TOKEN="your-token"
export SPRING_MAIL_USERNAME="your-email@example.com"
export SPRING_MAIL_PASSWORD="your-password"
export APP_NOTIFICATIONS_SMS_RECIPIENTS="phone1@gateway.com,phone2@gateway.com"
```

Then reference them in `application.yml`:
```yaml
google:
  weather:
    api-key: ${GOOGLE_WEATHER_API_KEY}
```

## Configuration
```yaml
google:
  calendar:
    credentials-path: file:/path/to/your/service-account.json #get one by creating a service account with access to routes/calendar apis in google cloud console
    application-name: Tesla Automatic Preconditioning
    name: REPLACE_WITH_YOURS@group.calendar.google.com
  maps:
    routes:
      field-mask: routes.distanceMeters,routes.duration,routes.routeLabels,routes.routeToken
  weather:
    api-key: YOUR_GOOGLE_WEATHER_API_KEY #https://developers.google.com/maps/documentation/weather and "get started", lots of free usage included
    base-url: https://weather.googleapis.com/v1
    units: IMPERIAL
teslemetry:
  oauth:
    token: YOUR_TESLEMETRY_TOKEN #change me, get yours @ https://teslemetry.com/
spring:
  mail:
    host: smtp.gmail.com
    port: 587
    username: YOUR_EMAIL_USERNAME #change me
    password: YOUR_EMAIL_PASSWORD #change me
    properties:
      mail.smtp.auth: true
      mail.smtp.starttls.enable: true
  datasource:
    url: jdbc:h2:mem:testdb
    username: sa
    password: password
  liquibase:
    enabled: true
    change-log: file:/resources/db/changelog/17-01-changelog.yaml
tesla:
  vin:
    csv: ASSIGNEE_EMAIL_ONE:TESLA_VIN_ONE,ASSIGNEE_EMAIL_ONE:TESLA_VIN_TWO
  home:
    lat: YOUR_HOME_LAT #change me, 12.3456789
    lon: YOUR_HOME_LON #change me, -12.3456789
  preconditioning:
    buffer:
      minutes: 10
    scheduling:
      out-of-metro-threshold-miles: 100
      rescheduling:
        enabled: true
        movement-threshold-miles: 25
        time-savings-threshold-minutes: 30
        minimum-time-remaining-hours: 2

app:
  api:
    secret-key: TO_SECURE_ENDPOINTS #change me

```

```bash
export GOOGLE_WEATHER_API_KEY="..."
export TESSIE_TOKEN="..."
```
### Enabling Google APIs & Keys
-	Enable Calendar, Routes, Weather APIs in GCP Console.
-	Create an API key for Weather in APIs & Services → Credentials.
-	Restrict the key (application & API restrictions).
-	For Calendar, provision a service account JSON, optionally with domain-wide delegation.

### How it works
1.	Load Calendar events.
2.	For each:
-	Compute route ETA (Google Routes).
-	Derive preconditioning start (ETA - buffer - duration).
-	Fetch recent weather, compute snow/ice risk with recency & temperature weighting.
3.	If appropriate, schedule preconditioning.
4.	After a configurable delay, verify with a new transaction that the schedule exists.

### Weather Risk API
```java
boolean highRisk = weatherService.hasHighSnowOrIceCoverageLast12h(
    lat, lon, Optional.of(WeatherService.UnitsSystem.IMPERIAL)
);
```
- Uses /history/hours:lookup for last 12h. 
- Weighted by recency (half-life ~6h) and temperature (melt/boost factors).
- Score normalized [0,1], high risk threshold ~0.55.
### Build & Test
```bash
./mvnw test
./mvnw package
```

Tests use Testcontainers for PostgreSQL.

## Troubleshooting
-	404 → ensure :lookup isn’t escaped (use .build(true)).
-	401/403 → check key restrictions & billing.
-	Calendar issues → verify JSON path & impersonation.
-	Tesla schedule missing → increase completion delay.

## Pushing Images
#### Vars
```bash
export GHCR_NS=javadevjt
export IMAGE_NAME=tesla-automatic-preconditioning
export IMAGE_ID=ghcr.io/${GHCR_NS}/${IMAGE_NAME}
export VERSION=0.0.39
```
#### Build
```
podman build --arch amd64 -t ${IMAGE_ID}:amd64-${VERSION} .
podman build --arch arm64 -t ${IMAGE_ID}:arm64-${VERSION} .
```
#### Push
```
podman push ${IMAGE_ID}:amd64-${VERSION}
podman push ${IMAGE_ID}:arm64-${VERSION}
```
#### Manifest
```
podman manifest rm ${IMAGE_ID}:${VERSION} 2>/dev/null || true
podman manifest create ${IMAGE_ID}:${VERSION}
podman manifest add    ${IMAGE_ID}:${VERSION} ${IMAGE_ID}:amd64-${VERSION}
podman manifest add    ${IMAGE_ID}:${VERSION} ${IMAGE_ID}:arm64-${VERSION}
podman manifest push   ${IMAGE_ID}:${VERSION}
```
#### Latest
```
podman manifest rm ${IMAGE_ID}:latest 2>/dev/null || true
podman manifest create ${IMAGE_ID}:latest
podman manifest add    ${IMAGE_ID}:latest ${IMAGE_ID}:amd64-${VERSION}
podman manifest add    ${IMAGE_ID}:latest ${IMAGE_ID}:arm64-${VERSION}
podman manifest push   ${IMAGE_ID}:latest
```

## Streamlined Container Build & Push

For efficiency, all container operations can be executed in a single command sequence:

```bash
export GHCR_NS=javadevjt && export IMAGE_NAME=tesla-automatic-preconditioning && export IMAGE_ID=ghcr.io/${GHCR_NS}/${IMAGE_NAME} && export VERSION=0.0.39 && \
echo "Starting container build and push process for version ${VERSION}" && \
echo "Building AMD64 image..." && podman build --arch amd64 -t ${IMAGE_ID}:amd64-${VERSION} . && \
echo "Building ARM64 image..." && podman build --arch arm64 -t ${IMAGE_ID}:arm64-${VERSION} . && \
echo "Pushing AMD64 image..." && podman push ${IMAGE_ID}:amd64-${VERSION} && \
echo "Pushing ARM64 image..." && podman push ${IMAGE_ID}:arm64-${VERSION} && \
echo "Creating version manifest..." && podman manifest rm ${IMAGE_ID}:${VERSION} 2>/dev/null || true && \
podman manifest create ${IMAGE_ID}:${VERSION} && \
podman manifest add ${IMAGE_ID}:${VERSION} ${IMAGE_ID}:amd64-${VERSION} && \
podman manifest add ${IMAGE_ID}:${VERSION} ${IMAGE_ID}:arm64-${VERSION} && \
echo "Pushing version manifest..." && podman manifest push ${IMAGE_ID}:${VERSION} && \
echo "Creating latest manifest..." && podman manifest rm ${IMAGE_ID}:latest 2>/dev/null || true && \
podman manifest create ${IMAGE_ID}:latest && \
podman manifest add ${IMAGE_ID}:latest ${IMAGE_ID}:amd64-${VERSION} && \
podman manifest add ${IMAGE_ID}:latest ${IMAGE_ID}:arm64-${VERSION} && \
echo "Pushing latest manifest..." && podman manifest push ${IMAGE_ID}:latest && \
echo "✅ All container operations completed successfully!"
```

### Benefits of Streamlined Approach

- **Single Command Execution**: Run all build and push operations without manual intervention
- **Progress Monitoring**: Clear echo statements show current operation status
- **Error Handling**: Uses `&&` to fail fast on any error
- **Manifest Cleanup**: Automatically removes existing manifests before recreation
- **Version Flexibility**: Simply update the `VERSION` variable for new releases

### Prerequisites

- **Podman/Docker**: Container runtime installed and authenticated to GHCR
- **Build Context**: Run from project root directory
- **Network**: Stable internet connection for image pulls/pushes
- **GHCR Access**: Proper permissions to push to `ghcr.io/javadevjt/tesla-automatic-preconditioning`
