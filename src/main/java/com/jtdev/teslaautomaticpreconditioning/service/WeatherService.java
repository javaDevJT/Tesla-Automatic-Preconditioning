package com.jtdev.teslaautomaticpreconditioning.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriBuilder;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Function;

/**
 * Thin wrapper over Google Maps Platform Weather API.
 * Reference:
 *  - Current conditions:  /v1/currentConditions:lookup
 *  - Hourly forecast:     /v1/forecast/hours:lookup
 *  - Daily forecast:      /v1/forecast/days:lookup
 *  - Hourly history:      /v1/history/hours:lookup  (up to 24 hours)
 *
 * Required property:
 *   google.weather.api-key=YOUR_KEY   (automatically appended by the WebClient bean)
 * Optional:
 *   google.weather.units=IMPERIAL|METRIC  (defaults to METRIC per API)
 */
@Service
@RequiredArgsConstructor
public class WeatherService {

    private final WebClient weatherWebClient;

    @Value("${google.weather.units:}")          // empty -> default API metric
    private String defaultUnits;

    @Value("${google.weather.timeout-seconds:6}")
    private int timeoutSeconds;

    private static class RetryableWeatherException extends RuntimeException {
        RetryableWeatherException(String message) { super(message); }
    }

    private boolean isRetryable(Throwable t) {
        return (t instanceof RetryableWeatherException);
    }

    private <T> Mono<T> handle(ClientResponse resp, Class<T> bodyType) {
        return resp
//                .onStatus(
//                        status -> status.is5xxServerError() || status.value() == 429,
//                        r -> r.bodyToMono(String.class)
//                                .defaultIfEmpty("Weather API error")
//                                .map(msg -> new RetryableWeatherException("Weather API " + r.statusCode() + ": " + msg))
//                )
//                .onStatus(status -> status.is4xxClientError() && status.value() != 429,
//                        r -> r.bodyToMono(String.class)
//                                .defaultIfEmpty("Weather API 4xx error")
//                                .map(msg -> new IllegalStateException("Weather API " + r.statusCode() + ": " + msg))
//                )
                .bodyToMono(bodyType);
    }

    public enum UnitsSystem { METRIC, IMPERIAL }

    /** IMperial or METRIC per docs (leave empty to get API default metric). */
    private Function<UriBuilder, UriBuilder> unitsParam(Optional<UnitsSystem> units) {
        return b -> units.map(Enum::name)
                .map(u -> b.queryParam("unitsSystem", u))
                .orElse(b);
    }

    private Function<UriBuilder, UriBuilder> latLng(double lat, double lng) {
        return b -> b.queryParam("location.latitude", lat)
                .queryParam("location.longitude", lng);
    }

    /** GET /currentConditions:lookup */
    public Mono<JsonNode> getCurrentConditions(double lat, double lng, Optional<UnitsSystem> unitsOpt) {
        return weatherWebClient.get()
                .uri(uriBuilder ->
                        unitsParam(unitsOpt.or(() -> Optional.ofNullable(defaultUnits).map(u -> {
                            try { return UnitsSystem.valueOf(u.toUpperCase()); } catch (Exception e) { return null; }
                        })))
                                .apply(latLng(lat, lng).apply(uriBuilder))
                                .path("/currentConditions:lookup")
                                .build(true))
                .exchangeToMono(resp -> handle(resp, JsonNode.class))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .retryWhen(Retry.backoff(3, Duration.ofMillis(200)).filter(this::isRetryable).transientErrors(true));
    }

    /** GET /forecast/hours:lookup (hours up to 240). Supports paging via pageSize/pageToken. */
    public Mono<JsonNode> getHourlyForecast(
            double lat,
            double lng,
            Optional<Integer> hours,
            Optional<Integer> pageSize,
            Optional<String> pageToken,
            Optional<UnitsSystem> unitsOpt) {

        return weatherWebClient.get()
                .uri(uriBuilder -> {
                    UriBuilder b = unitsParam(unitsOpt.or(() -> Optional.ofNullable(defaultUnits).map(u -> {
                        try { return UnitsSystem.valueOf(u.toUpperCase()); } catch (Exception e) { return null; }
                    })))
                            .apply(latLng(lat, lng).apply(uriBuilder))
                            .path("/forecast/hours:lookup");
                    hours.ifPresent(h -> b.queryParam("hours", h));
                    pageSize.ifPresent(ps -> b.queryParam("pageSize", ps));
                    pageToken.ifPresent(pt -> b.queryParam("pageToken", pt));
                    return b.build(true);
                })
                .exchangeToMono(resp -> handle(resp, JsonNode.class))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .retryWhen(Retry.backoff(3, Duration.ofMillis(200)).filter(this::isRetryable).transientErrors(true));
    }

    /** GET /forecast/days:lookup (returns up to 10 days). */
    public Mono<JsonNode> getDailyForecast(
            double lat,
            double lng,
            Optional<Integer> days,
            Optional<UnitsSystem> unitsOpt) {

        return weatherWebClient.get()
                .uri(uriBuilder -> {
                    UriBuilder b = unitsParam(unitsOpt.or(() -> Optional.ofNullable(defaultUnits).map(u -> {
                        try { return UnitsSystem.valueOf(u.toUpperCase()); } catch (Exception e) { return null; }
                    })))
                            .apply(latLng(lat, lng).apply(uriBuilder));
                    b = b.path("/forecast/days:lookup");
                    UriBuilder finalB = b;
                    days.ifPresent(d -> finalB.queryParam("days", d));
                    return finalB.build(true);
                })
                .exchangeToMono(resp -> handle(resp, JsonNode.class))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .retryWhen(Retry.backoff(3, Duration.ofMillis(200)).filter(this::isRetryable).transientErrors(true));
    }

    /** GET /history/hours:lookup (returns up to the prior 24 hours, starting from the last hour). */
    public Mono<JsonNode> getHourlyHistory(
            double lat,
            double lng,
            Optional<Integer> hours,
            Optional<UnitsSystem> unitsOpt) {

        // Per docs, history supports up to 24 hours. Clamp to [1, 24] if present.
        Optional<Integer> safeHours = hours.map(h -> Math.max(1, Math.min(24, h)));

        return weatherWebClient.get()
                .uri(uriBuilder -> {
                    UriBuilder b = unitsParam(unitsOpt.or(() -> Optional.ofNullable(defaultUnits).map(u -> {
                        try { return UnitsSystem.valueOf(u.toUpperCase()); } catch (Exception e) { return null; }
                    })))
                            .apply(latLng(lat, lng).apply(uriBuilder))
                            .path("/history/hours:lookup");
                    safeHours.ifPresent(h -> b.queryParam("hours", h));
                    return b.build(true);
                })
                .exchangeToMono(resp -> handle(resp, JsonNode.class))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .retryWhen(Retry.backoff(3, Duration.ofMillis(200)).filter(this::isRetryable).transientErrors(true));
    }

    /** Convenience: last 24 hours in one call (history). */
    public Mono<JsonNode> getLast24Hours(double lat, double lng, Optional<UnitsSystem> unitsOpt) {
        return getHourlyHistory(lat, lng, Optional.of(24), unitsOpt);
    }

    /** Synchronous convenience with timeout from config. */
    public JsonNode blockingCurrentConditions(double lat, double lng, Optional<UnitsSystem> unitsOpt) {
        return getCurrentConditions(lat, lng, unitsOpt).block(Duration.ofSeconds(timeoutSeconds));
    }


    public Mono<Double> snowIceCoverageRiskLastNHours(double lat,
                                                      double lng,
                                                      int hours,
                                                      Optional<UnitsSystem> unitsOpt) {
        int clamped = Math.max(1, Math.min(24, hours));

        // Tunables: how quickly older hours decay and how strongly above-freezing temps melt risk.
        double halfLifeHours = 6.0; // recent hours matter most; every +6h halves impact
        double meltPerDegC = 0.07;  // each °C above 0 reduces contribution by 7% (capped)
        double freezeBoostPerDegC = 0.02; // each °C below 0 boosts up to +20% at −10°C

        return getHourlyHistory(lat, lng, Optional.of(clamped), unitsOpt)
                .map(root -> {
                    double weightedRisky = 0.0;
                    double weightedQpfMm = 0.0;
                    double weightedFreeze = 0.0;
                    double weightedIce = 0.0;
                    double totalWeight = 0.0;

                    JsonNode hoursNode = root.path("historyHours");
                    if (!hoursNode.isArray() || hoursNode.isEmpty()) return 0.0;

                    // Determine now from the newest hour's time if available; fallback to Instant.now()
                    Instant now = Instant.now();
                    try {
                        String newestTs = hoursNode.get(0).path("time").asText(null);
                        if (newestTs != null) now = Instant.parse(newestTs);
                    } catch (Exception ignored) { /* keep now */ }

                    for (int i = 0; i < hoursNode.size(); i++) {
                        JsonNode h = hoursNode.get(i);

                        // Parse hour timestamp if present to compute a precise age; otherwise derive from index.
                        double ageHours;
                        try {
                            String ts = h.path("time").asText(null);
                            if (ts != null) {
                                Instant t = Instant.parse(ts);
                                ageHours = Math.max(0, Duration.between(t, now).toHours());
                            } else {
                                ageHours = i; // assume array ordered newest->oldest; if opposite, it only affects weighting slightly
                            }
                        } catch (Exception e) {
                            ageHours = i;
                        }

                        // Exponential decay weight with given half-life.
                        double decay = Math.pow(0.5, ageHours / halfLifeHours);

                        // precip probability + type
                        JsonNode prob = h.path("precipitation").path("probability");
                        int percent = prob.path("percent").asInt(0);
                        String type = prob.path("type").asText("NONE");
                        boolean isSnowOrIceType = "SNOW".equals(type)
                                || "RAIN_AND_SNOW".equals(type)
                                || "SLEET".equals(type)
                                || "FREEZING_RAIN".equals(type);

                        // qpf -> mm
                        JsonNode qpf = h.path("precipitation").path("qpf");
                        double qty = qpf.path("quantity").asDouble(0.0);
                        String unit = qpf.path("unit").asText("MILLIMETERS");
                        double qtyMm = "INCHES".equals(unit) ? qty * 25.4 : qty;

                        // temperature -> °C
                        JsonNode temp = h.path("temperature");
                        double deg = temp.path("degrees").asDouble(Double.NaN);
                        String tUnit = temp.path("unit").asText("CELSIUS");
                        double degC = "FAHRENHEIT".equals(tUnit) ? (deg - 32.0) * (5.0/9.0) : deg;

                        // ice thickness -> mm
                        JsonNode ice = h.path("iceThickness");
                        double iceThick = ice.path("thickness").asDouble(0.0);
                        String iceUnit = ice.path("unit").asText("MILLIMETERS");
                        double iceMm = "INCHES".equals(iceUnit) ? iceThick * 25.4 : iceThick;

                        // Temperature melt/boost factor for the hour.
                        double tempFactor = 1.0;
                        if (!Double.isNaN(degC)) {
                            if (degC > 0.0) {
                                // Reduce contribution linearly with temp above freezing; cap reduction at 80%.
                                double reduction = Math.min(0.8, Math.max(0.0, degC * meltPerDegC));
                                tempFactor = 1.0 - reduction;
                            } else if (degC < 0.0) {
                                // Slight boost for well-below-freezing; cap ~20% around -10C.
                                double boost = Math.min(0.2, Math.max(0.0, (-degC) * freezeBoostPerDegC));
                                tempFactor = 1.0 + boost;
                            }
                        }

                        double weight = decay * tempFactor;
                        totalWeight += weight;

                        boolean riskyHour = isSnowOrIceType && percent >= 50;
                        if (riskyHour) {
                            weightedRisky += weight;
                            weightedQpfMm += qtyMm * weight;
                        }
                        if (!Double.isNaN(degC) && degC <= 0.0) {
                            weightedFreeze += weight;
                        }
                        if (iceMm > 0.1) {
                            weightedIce += weight;
                        }
                    }

                    if (totalWeight == 0.0) return 0.0;

                    // Normalize the weighted components by totalWeight.
                    double hoursFrac  = weightedRisky / totalWeight;
                    // QPF threshold heuristic for "coverage": >= 1.5 mm (liq) after weighting → material
                    double qpfScore   = Math.min(1.0, weightedQpfMm / 1.5);
                    double freezeFrac = weightedFreeze / totalWeight;
                    double iceFrac    = weightedIce / totalWeight;

                    double risk = 0.45 * hoursFrac + 0.35 * qpfScore + 0.15 * freezeFrac + 0.05 * iceFrac;
                    return Math.max(0.0, Math.min(1.0, risk));
                })
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .retryWhen(Retry.backoff(3, Duration.ofMillis(200))
                        .filter(this::isRetryable)
                        .transientErrors(true));
    }

    /** Boolean convenience for “previous 12 hours” */
    public boolean hasHighSnowOrIceCoverageLast12h(double lat, double lng, Optional<UnitsSystem> unitsOpt) {
        Double score = snowIceCoverageRiskLastNHours(lat, lng, 12, unitsOpt)
                .block(Duration.ofSeconds(timeoutSeconds));
        return score != null && score >= 0.55;
    }
}