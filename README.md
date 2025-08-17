# Tesla-Automatic-Preconditioning

A Spring Framework 7 / Spring Boot 4 service that reads your calendar, predicts commute timing with Google Maps Routes, checks recent weather via Google Weather, and automatically manages **one-time preconditioning schedules** on your Tesla (via your Fleet/Tessie integration). It persists schedule links in PostgreSQL and uses Liquibase for schema management.

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
- Tesla Fleet/Tessie access token or integration

## Quick Start

```bash
# build
./mvnw clean package

# run
java -jar target/Tesla-Automatic-Preconditioning-0.0.1-SNAPSHOT.jar
```
## Configuration
```yaml
google:
  calendar:
    credentials-path: /resources/google/service-account.json
    application-name: Tesla Automatic Preconditioning
    impersonate: ""
    name: ##Find this in Google calendar settings for your desired calendar; It will look like <something>@calendar.google.com
  maps:
    routes:
      field-mask: routes.distanceMeters,routes.duration,routes.routeLabels,routes.routeToken
  weather:
    api-key: ${GOOGLE_WEATHER_API_KEY}
    base-url: https://weather.googleapis.com/v1
    units: IMPERIAL
    timeout-seconds: 6

tessie:
  oauth:
    token: ${TESSIE_TOKEN}

spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/postgres
    username: postgres
    password: password
  liquibase:
    enabled: true
    change-log: /resources/db/changelog/17-01-changelog.yaml

tesla:
  vin:
    csv: email@example.com:5YJ...886,another@example.com:7G2...060
  home:
    lat: 42.1231231231
    lon: -83.1231231231
  preconditioning:
    buffer:
      minutes: 10
    completion:
      delay-minutes: 10
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


