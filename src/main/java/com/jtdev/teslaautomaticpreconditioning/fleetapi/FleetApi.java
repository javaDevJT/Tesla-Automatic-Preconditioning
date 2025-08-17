package com.jtdev.teslaautomaticpreconditioning.fleetapi;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/*
 * TeslaFleetApiClient.java
 *
 * Minimal, dependency‑light Java 11+ wrapper for the Tesla Fleet API.
 *
 * Features
 * - Covers ALL documented REST endpoints as of Aug 15, 2025 across:
 *   User, Partner, Energy, Charging, Vehicle Endpoints, Vehicle Commands.
 * - Region handling (NA/EU/CN) with optional auto‑detection.
 * - Optional routing of Vehicle Commands via a Vehicle Command Proxy (identical paths).
 * - Simple retry/backoff on 429/5xx honoring Retry-After / RateLimit-Reset.
 * - Returns raw JSON (String) to avoid external JSON deps; accepts body as Map/List/String.
 *
 * Usage
 *   TeslaFleetApiClient client = TeslaFleetApiClient.newBuilder()
 *       .accessToken("YOUR_BEARER_TOKEN")
 *       .region(TeslaFleetApiClient.Region.NA) // or .autoDetectRegion()
 *       .build();
 *
 *   String vehicles = client.vehiclesList(null); // GET /api/1/vehicles
 *   String vd = client.vehicleData("5YJ3E1EA7JF000000", Map.of("location_data", "true"));
 *   String start = client.commandChargeStart("5YJ3E1EA7JF000000", null);
 *
 * Notes
 * - vehicleTag may be VIN or numeric id (see Tesla docs). Pass as String.
 * - Some endpoints expect bodies; pass a Map<String,Object>, List<?>, or raw JSON String.
 * - This wrapper does not implement OAuth; supply a valid access token.
 */


public class FleetApi {
    // ------------------------------ Core HTTP helpers ------------------------------
    // ------------------------------ Tessie API helpers ------------------------------
    private static final String TESSIE_BASE_URL = "https://api.tessie.com";
    private final Region region;
    private final boolean autoDetectRegion;
    private final String accessToken;
    private final HttpClient http;
    private final Duration timeout;
    private final String userAgent;
    private final boolean logRequests;
    private final boolean useCommandsProxy;
    private final String commandsProxyBaseUrl;
    private final int maxRetries;
    // Instance fields
    private volatile String baseUrl;
    private FleetApi(String accessToken, String baseUrl, Region region, boolean autoDetectRegion,
                     Duration timeout, String userAgent, boolean logRequests,
                     boolean useCommandsProxy, String commandsProxyBaseUrl, int maxRetries) {
        this.accessToken = accessToken;
        this.baseUrl = baseUrl;
        this.region = region;
        this.autoDetectRegion = autoDetectRegion;
        this.timeout = timeout;
        this.userAgent = userAgent;
        this.logRequests = logRequests;
        this.useCommandsProxy = useCommandsProxy;
        this.commandsProxyBaseUrl = commandsProxyBaseUrl;
        this.maxRetries = maxRetries;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String stringifyJson(Object body) {
        if (body == null) return "{}";
        if (body instanceof String) return (String) body;
        if (body instanceof Map) return toJsonFromMap((Map<?, ?>) body);
        if (body instanceof List) return toJsonFromList((List<?>) body);
        // Fallback: use toString (assume already JSON)
        return body.toString();
    }

    // Very small JSON builder for Maps/Lists of primitives/strings
    private static String toJsonFromMap(Map<?, ?> map) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(escape(String.valueOf(e.getKey()))).append('"').append(':').append(toJsonValue(e.getValue()));
        }
        sb.append('}');
        return sb.toString();
    }

    private static String toJsonFromList(List<?> list) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        boolean first = true;
        for (Object o : list) {
            if (!first) sb.append(',');
            first = false;
            sb.append(toJsonValue(o));
        }
        sb.append(']');
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static String toJsonValue(Object v) {
        if (v == null) return "null";
        if (v instanceof String) return '"' + escape((String) v) + '"';
        if (v instanceof Number || v instanceof Boolean) return v.toString();
        if (v instanceof Map) return toJsonFromMap((Map<?, ?>) v);
        if (v instanceof List) return toJsonFromList((List<?>) v);
        return '"' + escape(v.toString()) + '"';
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static long computeRetryDelay(String retryAfter, String rateLimitReset, int attempt) {
        // honor explicit hints; otherwise exponential backoff
        try {
            if (retryAfter != null) {
                // Retry-After seconds
                return Math.max(0, Long.parseLong(retryAfter.trim()) * 1000L);
            }
        } catch (NumberFormatException ignore) {
        }
        try {
            if (rateLimitReset != null) {
                long now = System.currentTimeMillis() / 1000L;
                long reset = Long.parseLong(rateLimitReset.trim());
                long delta = Math.max(0, (reset - now) * 1000L);
                if (delta > 0) return delta;
            }
        } catch (NumberFormatException ignore) {
        }
        // fallback exponential: 0.5s, 1s, 2s, 4s ...
        return (long) (500L * Math.pow(2, Math.max(0, attempt - 1)));
    }

    private static String extractBaseUrl(String json) {
        if (json == null) return null;
        // naive find of a fleet-api URL
        int i = json.indexOf("fleet-api.prd");
        if (i >= 0) {
            int start = json.lastIndexOf('"', i);
            int end = json.indexOf('"', i);
            if (start >= 0 && end > start) return json.substring(start + 1, end);
        }
        return null;
    }

    private String tessieBuildUrl(String path, Map<String, String> query) {
        StringBuilder sb = new StringBuilder();
        sb.append(TESSIE_BASE_URL);
        if (!path.startsWith("/")) sb.append('/');
        sb.append(path);
        if (query != null && !query.isEmpty()) {
            boolean first = true;
            for (Map.Entry<String, String> e : query.entrySet()) {
                if (e.getValue() == null) continue;
                sb.append(first ? '?' : '&');
                first = false;
                sb.append(encode(e.getKey())).append('=').append(encode(e.getValue()));
            }
        }
        return sb.toString();
    }

    private String tessieGet(String path, Map<String, String> query) {
        String url = tessieBuildUrl(path, query);
        HttpRequest req = baseRequestBuilder(url).GET().build();
        try {
            return sendWithRetries(req);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String tessiePost(String path, Object body) {
        String url = tessieBuildUrl(path, null);
        String json = stringifyJson(body);
        HttpRequest req = baseRequestBuilder(url).POST(HttpRequest.BodyPublishers.ofString(json)).build();
        try {
            return sendWithRetries(req);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String tessieDelete(String path) {
        String url = tessieBuildUrl(path, null);
        HttpRequest req = baseRequestBuilder(url).DELETE().build();
        try {
            return sendWithRetries(req);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String buildUrl(String base, String path, Map<String, String> query) {
        StringBuilder sb = new StringBuilder();
        sb.append(base);
        if (!path.startsWith("/")) sb.append('/');
        sb.append(path);
        if (query != null && !query.isEmpty()) {
            boolean first = true;
            for (Map.Entry<String, String> e : query.entrySet()) {
                if (e.getValue() == null) continue;
                sb.append(first ? '?' : '&');
                first = false;
                sb.append(encode(e.getKey())).append('=').append(encode(e.getValue()));
            }
        }
        return sb.toString();
    }

    private HttpRequest.Builder baseRequestBuilder(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .header("User-Agent", userAgent);
    }

    private String sendWithRetries(HttpRequest request) throws IOException, InterruptedException {
        int attempt = 0;
        IOException lastIo = null;
        InterruptedException lastInt = null;
        while (attempt <= maxRetries) {
            attempt++;
            if (logRequests) System.out.println("➡ " + request.method() + " " + request.uri());
            
            try {
                HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
                int code = resp.statusCode();
                if (logRequests)
                    System.out.println("⬅ [" + code + "] " + (resp.body() != null ? Math.min(resp.body().length(), 200) : 0) + " bytes");
                if (code < 500 && code != 429) return resp.body();
                
                // retry on 429/5xx
                long sleepMs = computeRetryDelay(resp.headers().firstValue("Retry-After").orElse(null),
                        resp.headers().firstValue("RateLimit-Reset").orElse(null),
                        attempt);
                if (attempt > maxRetries) return resp.body();
                Thread.sleep(sleepMs);
                
            } catch (java.net.http.HttpTimeoutException | java.net.SocketTimeoutException e) {
                lastIo = new IOException("Request timeout on attempt " + attempt, e);
                if (logRequests) System.out.println("⚠️ Timeout on attempt " + attempt + ", retrying...");
                
                if (attempt > maxRetries) break; // Exit retry loop if max attempts reached
                
                // Use default retry delay for timeout
                long sleepMs = computeDefaultRetryDelay(attempt);
                Thread.sleep(sleepMs);
                
            } catch (IOException e) {
                lastIo = e;
                if (logRequests) System.out.println("⚠️ IOException on attempt " + attempt + ": " + e.getMessage());
                
                if (attempt > maxRetries) break;
                
                long sleepMs = computeDefaultRetryDelay(attempt);
                Thread.sleep(sleepMs);
                
            } catch (InterruptedException e) {
                lastInt = e;
                Thread.currentThread().interrupt(); // Restore interrupt status
                break; // Don't retry on interruption
            }
        }
        if (lastIo != null) throw lastIo;
        if (lastInt != null) throw lastInt;
        throw new IOException("Request failed after retries");
    }
    
    private long computeDefaultRetryDelay(int attempt) {
        // Exponential backoff: 2^attempt seconds, capped at 60 seconds
        long baseDelayMs = Math.min(1000L * (1L << attempt), 60000L);
        // Add jitter to avoid thundering herd
        long jitterMs = (long) (Math.random() * 1000);
        return baseDelayMs + jitterMs;
    }

    private String get(String path, Map<String, String> query) {
        String url = buildUrl(baseUrl, path, query);
        HttpRequest req = baseRequestBuilder(url).GET().build();
        try {
            return sendWithRetries(req);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String delete(String path, Map<String, String> query, Object body) {
        String url = buildUrl(baseUrl, path, query);
        String json = stringifyJson(body);
        HttpRequest req = baseRequestBuilder(url)
                .method("DELETE", HttpRequest.BodyPublishers.ofString(json))
                .build();
        try {
            return sendWithRetries(req);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String post(String path, Map<String, String> query, Object body) {
        String url = buildUrl(baseUrl, path, query);
        String json = stringifyJson(body);
        HttpRequest req = baseRequestBuilder(url).POST(HttpRequest.BodyPublishers.ofString(json)).build();
        try {
            return sendWithRetries(req);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String commandPost(String vehicleTag, String commandPath, Object body) {
        String relative = "/vehicles/" + vehicleTag + "/command/" + commandPath;
        if (useCommandsProxy && commandsProxyBaseUrl != null && !commandsProxyBaseUrl.isBlank()) {
            // send to proxy with identical path
            String url = buildUrl(commandsProxyBaseUrl, relative, null);
            String json = stringifyJson(body);
            HttpRequest req = baseRequestBuilder(url).POST(HttpRequest.BodyPublishers.ofString(json)).build();
            try {
                return sendWithRetries(req);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return post(relative, null, body);
    }

    /**
     * Attempts to detect and set correct baseUrl using /api/1/users/region across NA/EU/CN.
     */
    public synchronized void autodetectRegionNow() {
        List<Region> order = List.of(this.region, Region.NA, Region.EU, Region.CN);
        for (Region r : order) {
            try {
                String prev = this.baseUrl;
                this.baseUrl = r.baseUrl;
                String resp = get("/api/1/users/region", null);
                // Try to extract a base URL from the response JSON heuristically
                String bu = extractBaseUrl(resp);
                if (bu != null && !bu.isBlank()) {
                    this.baseUrl = bu;
                    return;
                }
                // else keep current region
                this.baseUrl = r.baseUrl;
                return; // 200 OK but unknown shape; stick to this region
            } catch (RuntimeException ex) {
                // try next region on 4xx/5xx
            }
        }
        // fallback: keep existing baseUrl
    }

    // ------------------------------ Region autodetect ------------------------------

    /**
     * GET /status on current baseUrl
     */
    public String status() {
        return rawGet(baseUrl + "/status");
    }

    private String rawGet(String url) {
        HttpRequest req = baseRequestBuilder(url).GET().build();
        try {
            return sendWithRetries(req);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ------------------------------ Convenience: health ------------------------------

    /**
     * GET /api/1/users/feature_config
     */
    public String usersFeatureConfig() {
        return get("/api/1/users/feature_config", null);
    }

    /**
     * GET /api/1/users/me
     */
    public String usersMe() {
        return get("/api/1/users/me", null);
    }

    // ==================================================================================
    //                                  USER ENDPOINTS
    // ==================================================================================

    /**
     * GET /api/1/users/orders
     */
    public String usersOrders() {
        return get("/api/1/users/orders", null);
    }

    /**
     * GET /api/1/users/region
     */
    public String usersRegion() {
        return get("/api/1/users/region", null);
    }

    /**
     * GET /api/1/partner_accounts/fleet_telemetry_error_vins
     */
    public String partnerFleetTelemetryErrorVins() {
        return get("/api/1/partner_accounts/fleet_telemetry_error_vins", null);
    }

    /**
     * GET /api/1/partner_accounts/fleet_telemetry_errors
     */
    public String partnerFleetTelemetryErrors() {
        return get("/api/1/partner_accounts/fleet_telemetry_errors", null);
    }

    // ==================================================================================
    //                                PARTNER ENDPOINTS
    // ==================================================================================

    /**
     * GET /api/1/partner_accounts/public_key?domain={domain}
     */
    public String partnerPublicKey(String domain) {
        return get("/api/1/partner_accounts/public_key", Map.of("domain", domain));
    }

    /**
     * POST /api/1/partner_accounts
     */
    public String partnerRegister(Object body) {
        return post("/api/1/partner_accounts", null, body);
    }

    /**
     * POST /api/1/energy_sites/{energy_site_id}/backup
     */
    public String energyBackup(String energySiteId, Object body) {
        return post("/api/1/energy_sites/" + energySiteId + "/backup", null, body);
    }

    /**
     * GET /api/1/energy_sites/{id}/calendar_history?kind=backup&start_date=...
     */
    public String energyBackupHistory(String energySiteId, Map<String, String> query) {
        return get("/api/1/energy_sites/" + energySiteId + "/calendar_history", query);
    }

    // ==================================================================================
    //                                ENERGY ENDPOINTS
    // ==================================================================================

    /**
     * GET /api/1/energy_sites/{id}/telemetry_history?kind=charge&...
     */
    public String energyChargeHistory(String energySiteId, Map<String, String> query) {
        return get("/api/1/energy_sites/" + energySiteId + "/telemetry_history", query);
    }

    /**
     * GET /api/1/energy_sites/{id}/calendar_history?kind=energy&...
     */
    public String energyEnergyHistory(String energySiteId, Map<String, String> query) {
        return get("/api/1/energy_sites/" + energySiteId + "/calendar_history", query);
    }

    /**
     * POST /api/1/energy_sites/{id}/grid_import_export
     */
    public String energyGridImportExport(String energySiteId, Object body) {
        return post("/api/1/energy_sites/" + energySiteId + "/grid_import_export", null, body);
    }

    /**
     * GET /api/1/energy_sites/{id}/live_status
     */
    public String energyLiveStatus(String energySiteId) {
        return get("/api/1/energy_sites/" + energySiteId + "/live_status", null);
    }

    /**
     * POST /api/1/energy_sites/{id}/off_grid_vehicle_charging_reserve
     */
    public String energyOffGridVehicleChargingReserve(String energySiteId, Object body) {
        return post("/api/1/energy_sites/" + energySiteId + "/off_grid_vehicle_charging_reserve", null, body);
    }

    /**
     * POST /api/1/energy_sites/{id}/operation
     */
    public String energyOperation(String energySiteId, Object body) {
        return post("/api/1/energy_sites/" + energySiteId + "/operation", null, body);
    }

    /**
     * GET /api/1/products
     */
    public String energyProducts() {
        return get("/api/1/products", null);
    }

    /**
     * GET /api/1/energy_sites/{id}/site_info
     */
    public String energySiteInfo(String energySiteId) {
        return get("/api/1/energy_sites/" + energySiteId + "/site_info", null);
    }

    /**
     * POST /api/1/energy_sites/{id}/storm_mode
     */
    public String energyStormMode(String energySiteId, Object body) {
        return post("/api/1/energy_sites/" + energySiteId + "/storm_mode", null, body);
    }

    /**
     * POST /api/1/energy_sites/{id}/time_of_use_settings
     */
    public String energyTimeOfUseSettings(String energySiteId, Object body) {
        return post("/api/1/energy_sites/" + energySiteId + "/time_of_use_settings", null, body);
    }

    /**
     * GET /api/1/dx/charging/history
     */
    public String chargingHistory(Map<String, String> query) {
        return get("/api/1/dx/charging/history", query);
    }

    /**
     * GET /api/1/dx/charging/invoice/{id}
     */
    public String chargingInvoice(String id) {
        return get("/api/1/dx/charging/invoice/" + encode(id), null);
    }

    // ==================================================================================
    //                               CHARGING ENDPOINTS
    // ==================================================================================

    /**
     * GET /api/1/dx/charging/sessions (business fleets only)
     */
    public String chargingSessions(Map<String, String> query) {
        return get("/api/1/dx/charging/sessions", query);
    }

    /**
     * GET /api/1/vehicles/{vehicle_tag}/drivers
     */
    public String vehicleDrivers(String vehicleTag) {
        return get("/api/1/vehicles/" + vehicleTag + "/drivers", null);
    }

    /**
     * DELETE /api/1/vehicles/{vehicle_tag}/drivers
     */
    public String vehicleDriversRemove(String vehicleTag, Object body) {
        return delete("/api/1/vehicles/" + vehicleTag + "/drivers", null, body);
    }

    // ==================================================================================
    //                              VEHICLE ENDPOINTS
    // ==================================================================================


    public void forceRefresh(String vin) {
        get("/api/refresh/" + vin, null);
    }
    /**
     * GET /api/1/dx/vehicles/subscriptions/eligibility?vin={vin}
     */
    public String vehiclesEligibleSubscriptions(String vin) {
        return get("/api/1/dx/vehicles/subscriptions/eligibility", Map.of("vin", vin));
    }

    /**
     * GET /api/1/dx/vehicles/upgrades/eligibility?vin={vin}
     */
    public String vehiclesEligibleUpgrades(String vin) {
        return get("/api/1/dx/vehicles/upgrades/eligibility", Map.of("vin", vin));
    }

    /**
     * POST /api/1/vehicles/fleet_status
     */
    public String vehiclesFleetStatus(Object body) {
        return post("/api/1/vehicles/fleet_status", null, body);
    }

    /**
     * POST /api/1/vehicles/fleet_telemetry_config
     */
    public String vehiclesFleetTelemetryConfigCreate(Object body) {
        return post("/api/1/vehicles/fleet_telemetry_config", null, body);
    }

    /**
     * DELETE /api/1/vehicles/{vehicle_tag}/fleet_telemetry_config
     */
    public String vehiclesFleetTelemetryConfigDelete(String vehicleTag) {
        return delete("/api/1/vehicles/" + vehicleTag + "/fleet_telemetry_config", null, null);
    }

    /**
     * GET /api/1/vehicles/{vehicle_tag}/fleet_telemetry_config
     */
    public String vehiclesFleetTelemetryConfigGet(String vehicleTag) {
        return get("/api/1/vehicles/" + vehicleTag + "/fleet_telemetry_config", null);
    }

    /**
     * POST /api/1/vehicles/fleet_telemetry_config_jws
     */
    public String vehiclesFleetTelemetryConfigJws(Object body) {
        return post("/api/1/vehicles/fleet_telemetry_config_jws", null, body);
    }

    /**
     * GET /api/1/vehicles/{vehicle_tag}/fleet_telemetry_errors
     */
    public String vehiclesFleetTelemetryErrors(String vehicleTag) {
        return get("/api/1/vehicles/" + vehicleTag + "/fleet_telemetry_errors", null);
    }

    /**
     * GET /api/1/vehicles
     */
    public String vehiclesList(Map<String, String> query) {
        return get("/api/1/vehicles", query);
    }

    /**
     * GET /api/1/vehicles/{vehicle_tag}/mobile_enabled
     */
    public String vehiclesMobileEnabled(String vehicleTag) {
        return get("/api/1/vehicles/" + vehicleTag + "/mobile_enabled", null);
    }

    /**
     * GET /api/1/vehicles/{vehicle_tag}/nearby_charging_sites
     */
    public String vehiclesNearbyChargingSites(String vehicleTag) {
        return get("/api/1/vehicles/" + vehicleTag + "/nearby_charging_sites", null);
    }

    /**
     * GET /api/1/dx/vehicles/options?vin={vin}
     */
    public String vehiclesOptions(String vin) {
        return get("/api/1/dx/vehicles/options", Map.of("vin", vin));
    }

    /**
     * GET /api/1/vehicles/{vehicle_tag}/recent_alerts
     */
    public String vehiclesRecentAlerts(String vehicleTag) {
        return get("/api/1/vehicles/" + vehicleTag + "/recent_alerts", null);
    }

    /**
     * GET /api/1/vehicles/{vehicle_tag}/release_notes
     */
    public String vehiclesReleaseNotes(String vehicleTag) {
        return get("/api/1/vehicles/" + vehicleTag + "/release_notes", null);
    }

    /**
     * GET /api/1/vehicles/{vehicle_tag}/service_data
     */
    public String vehiclesServiceData(String vehicleTag) {
        return get("/api/1/vehicles/" + vehicleTag + "/service_data", null);
    }

    /**
     * GET /api/1/vehicles/{vehicle_tag}/invitations
     */
    public String vehiclesShareInvites(String vehicleTag) {
        return get("/api/1/vehicles/" + vehicleTag + "/invitations", null);
    }

    /**
     * POST /api/1/vehicles/{vehicle_tag}/invitations
     */
    public String vehiclesShareInvitesCreate(String vehicleTag, Object body) {
        return post("/api/1/vehicles/" + vehicleTag + "/invitations", null, body);
    }

    /**
     * POST /api/1/invitations/redeem
     */
    public String vehiclesShareInvitesRedeem(Object body) {
        return post("/api/1/invitations/redeem", null, body);
    }

    /**
     * POST /api/1/vehicles/{vehicle_tag}/invitations/{id}/revoke
     */
    public String vehiclesShareInvitesRevoke(String vehicleTag, String id) {
        return post("/api/1/vehicles/" + vehicleTag + "/invitations/" + encode(id) + "/revoke", null, "{}");
    }

    /**
     * POST /api/1/vehicles/{vehicle_tag}/signed_command
     */
    public String vehiclesSignedCommand(String vehicleTag, Object body) {
        return post("/api/1/vehicles/" + vehicleTag + "/signed_command", null, body);
    }

    /**
     * GET /api/1/subscriptions
     */
    public String subscriptions() {
        return get("/api/1/subscriptions", null);
    }

    /**
     * POST /api/1/subscriptions
     */
    public String subscriptionsSet(Object body) {
        return post("/api/1/subscriptions", null, body);
    }

    /**
     * GET /api/1/vehicles/{vehicle_tag}
     */
    public String vehicle(String vehicleTag) {
        return get("/api/1/vehicles/" + vehicleTag, null);
    }

    /**
     * GET /api/1/vehicles/{vehicle_tag}/vehicle_data
     */
    public String vehicleData(String vehicleTag, Map<String, String> query) {
        return get("/vehicles/" + vehicleTag + "/vehicle_data", query);
    }

    /**
     * GET /api/1/vehicle_subscriptions
     */
    public String vehicleSubscriptions() {
        return get("/api/1/vehicle_subscriptions", null);
    }

    /**
     * POST /api/1/vehicle_subscriptions
     */
    public String vehicleSubscriptionsSet(Object body) {
        return post("/api/1/vehicle_subscriptions", null, body);
    }

    /**
     * POST /api/1/vehicles/{vehicle_tag}/wake_up
     */
    public String vehiclesWakeUp(String vehicleTag) {
        return post("/vehicles/" + vehicleTag + "/wake_up", null, "{}");
    }

    /**
     * GET /api/1/dx/warranty/details
     */
    public String vehiclesWarrantyDetails() {
        return get("/api/1/dx/warranty/details", null);
    }

    // ==================================================================================
    //                              VEHICLE COMMANDS
    // ==================================================================================
    public String commandActuateTrunk(String vehicleTag, Object body) {
        return commandPost(vehicleTag, "actuate_trunk", body);
    }

    public String commandAddChargeSchedule(String vehicleTag, Object body) {
        return commandPost(vehicleTag, "add_charge_schedule", body);
    }

    public String commandAddPreconditionSchedule(String vehicleTag, Object body) {
        return commandPost(vehicleTag, "add_precondition_schedule", body);
    }

    public String commandAdjustVolume(String vehicleTag, Object body) {
        return commandPost(vehicleTag, "adjust_volume", body);
    }

    public String commandAutoConditioningStart(String vehicleTag) {
        return commandPost(vehicleTag, "auto_conditioning_start", "{}");
    }

    public String commandAutoConditioningStop(String vehicleTag) {
        return commandPost(vehicleTag, "auto_conditioning_stop", "{}");
    }

    public String commandCancelSoftwareUpdate(String vehicleTag) {
        return commandPost(vehicleTag, "cancel_software_update", "{}");
    }

    public String commandChargeMaxRange(String vehicleTag) {
        return commandPost(vehicleTag, "charge_max_range", "{}");
    }

    public String commandChargePortDoorClose(String vehicleTag) {
        return commandPost(vehicleTag, "charge_port_door_close", "{}");
    }

    public String commandChargePortDoorOpen(String vehicleTag) {
        return commandPost(vehicleTag, "charge_port_door_open", "{}");
    }

    public String commandChargeStandard(String vehicleTag) {
        return commandPost(vehicleTag, "charge_standard", "{}");
    }

    public String commandChargeStart(String vehicleTag, Object body) {
        return commandPost(vehicleTag, "charge_start", body);
    }

    public String commandChargeStop(String vehicleTag) {
        return commandPost(vehicleTag, "charge_stop", "{}");
    }

    public String commandClearPinToDriveAdmin(String vehicleTag) {
        return commandPost(vehicleTag, "clear_pin_to_drive_admin", "{}");
    }

    public String commandDoorLock(String vehicleTag) {
        return commandPost(vehicleTag, "door_lock", "{}");
    }

    public String commandDoorUnlock(String vehicleTag) {
        return commandPost(vehicleTag, "door_unlock", "{}");
    }

    public String commandEraseUserData(String vehicleTag) {
        return commandPost(vehicleTag, "erase_user_data", "{}");
    }

    public String commandFlashLights(String vehicleTag) {
        return commandPost(vehicleTag, "flash_lights", "{}");
    }

    public String commandGuestMode(String vehicleTag, Object body) {
        return commandPost(vehicleTag, "guest_mode", body);
    }

    public String commandHonkHorn(String vehicleTag) {
        return commandPost(vehicleTag, "honk_horn", "{}");
    }

    public String commandMediaNextFav(String vehicleTag) {
        return commandPost(vehicleTag, "media_next_fav", "{}");
    }

    public String commandMediaNextTrack(String vehicleTag) {
        return commandPost(vehicleTag, "media_next_track", "{}");
    }

    public String commandMediaPrevFav(String vehicleTag) {
        return commandPost(vehicleTag, "media_prev_fav", "{}");
    }

    public String commandMediaPrevTrack(String vehicleTag) {
        return commandPost(vehicleTag, "media_prev_track", "{}");
    }

    public String commandMediaTogglePlayback(String vehicleTag) {
        return commandPost(vehicleTag, "media_toggle_playback", "{}");
    }

    public String commandMediaVolumeDown(String vehicleTag) {
        return commandPost(vehicleTag, "media_volume_down", "{}");
    }

    public String commandNavigationGpsRequest(String vehicleTag, Object body) {
        return commandPost(vehicleTag, "navigation_gps_request", body);
    }

    public String commandNavigationRequest(String vehicleTag, Object body) {
        return commandPost(vehicleTag, "navigation_request", body);
    }

    public String commandNavigationScRequest(String vehicleTag, Object body) {
        return commandPost(vehicleTag, "navigation_sc_request", body);
    }

    public String commandNavigationWaypointsRequest(String vehicleTag, Object body) {
        return commandPost(vehicleTag, "navigation_waypoints_request", body);
    }

    public String commandRemoteAutoSeatClimateRequest(String vehicleTag, Object body) {
        return commandPost(vehicleTag, "remote_auto_seat_climate_request", body);
    }

    public String commandRemoteAutoSteeringWheelHeatClimateRequest(String vehicleTag, Object body) {
        return commandPost(vehicleTag, "remote_auto_steering_wheel_heat_climate_request", body);
    }

    public String commandRemoteBoombox(String vehicleTag, Object body) {
        return commandPost(vehicleTag, "remote_boombox", body);
    }

    public String commandRemoteSeatCoolerRequest(String vehicleTag, Object body) {
        return commandPost(vehicleTag, "remote_seat_cooler_request", body);
    }

    public String commandRemoteSeatHeaterRequest(String vehicleTag, Object body) {
        return commandPost(vehicleTag, "remote_seat_heater_request", body);
    }

    public String commandRemoteStartDrive(String vehicleTag) {
        return commandPost(vehicleTag, "remote_start_drive", "{}");
    }

    public String commandRemoteSteeringWheelHeatLevelRequest(String vehicleTag, Object body) {
        return commandPost(vehicleTag, "remote_steering_wheel_heat_level_request", body);
    }

    public String commandRemoteSteeringWheelHeaterRequest(String vehicleTag, Object body) {
        return commandPost(vehicleTag, "remote_steering_wheel_heater_request", body);
    }

    public String commandRemoveChargeSchedule(String vehicleTag, Object body) {
        return commandPost(vehicleTag, "remove_charge_schedule", body);
    }

    public String commandRemovePreconditionSchedule(String vehicleTag, Object body) {
        return commandPost(vehicleTag, "remove_precondition_schedule", body);
    }

    public String commandResetPinToDrivePin(String vehicleTag) {
        return commandPost(vehicleTag, "reset_pin_to_drive_pin", "{}");
    }

    public String commandResetValetPin(String vehicleTag) {
        return commandPost(vehicleTag, "reset_valet_pin", "{}");
    }

    public String commandScheduleSoftwareUpdate(String vehicleTag, Object body) {
        return commandPost(vehicleTag, "schedule_software_update", body);
    }

    public String commandSetBioweaponMode(String vehicleTag, Object body) {
        return commandPost(vehicleTag, "set_bioweapon_mode", body);
    }

    public String commandSetCabinOverheatProtection(String vehicleTag, Object body) {
        return commandPost(vehicleTag, "set_cabin_overheat_protection", body);
    }

    public String commandSetChargeLimit(String vehicleTag, Object body) {
        return commandPost(vehicleTag, "set_charge_limit", body);
    }

    public String commandSetChargingAmps(String vehicleTag, Object body) {
        return commandPost(vehicleTag, "set_charging_amps", body);
    }

    public String commandSetClimateKeeperMode(String vehicleTag, Object body) {
        return commandPost(vehicleTag, "set_climate_keeper_mode", body);
    }

    public String commandSetCopTemp(String vehicleTag, Object body) {
        return commandPost(vehicleTag, "set_cop_temp", body);
    }

    public String commandSetPinToDrive(String vehicleTag, Object body) {
        return commandPost(vehicleTag, "set_pin_to_drive", body);
    }

    public String commandSetPreconditioningMax(String vehicleTag, Object body) {
        return commandPost(vehicleTag, "set_preconditioning_max", body);
    }

    /**
     * Deprecated after 2024.26 (use add_charge_schedule)
     */
    public String commandSetScheduledCharging(String vehicleTag, Object body) {
        return commandPost(vehicleTag, "set_scheduled_charging", body);
    }

    /**
     * Deprecated after 2024.26 (use add_precondition_schedule)
     */
    public String commandSetScheduledDeparture(String vehicleTag, Object body) {
        return commandPost(vehicleTag, "set_scheduled_departure", body);
    }

    public String commandSetSentryMode(String vehicleTag, Object body) {
        return commandPost(vehicleTag, "set_sentry_mode", body);
    }

    public String commandSetTemps(String vehicleTag, Object body) {
        return commandPost(vehicleTag, "set_temps", body);
    }

    public String commandSetValetMode(String vehicleTag, Object body) {
        return commandPost(vehicleTag, "set_valet_mode", body);
    }

    public String commandSetVehicleName(String vehicleTag, Object body) {
        return commandPost(vehicleTag, "set_vehicle_name", body);
    }

    public String commandSpeedLimitActivate(String vehicleTag, Object body) {
        return commandPost(vehicleTag, "speed_limit_activate", body);
    }

    public String commandSpeedLimitClearPin(String vehicleTag) {
        return commandPost(vehicleTag, "speed_limit_clear_pin", "{}");
    }

    public String commandSpeedLimitClearPinAdmin(String vehicleTag) {
        return commandPost(vehicleTag, "speed_limit_clear_pin_admin", "{}");
    }

    public String commandSpeedLimitDeactivate(String vehicleTag) {
        return commandPost(vehicleTag, "speed_limit_deactivate", "{}");
    }

    public String commandSpeedLimitSetLimit(String vehicleTag, Object body) {
        return commandPost(vehicleTag, "speed_limit_set_limit", body);
    }

    public String commandSunRoofControl(String vehicleTag, Object body) {
        return commandPost(vehicleTag, "sun_roof_control", body);
    }

    public String commandTriggerHomelink(String vehicleTag, Object body) {
        return commandPost(vehicleTag, "trigger_homelink", body);
    }

    public String commandUpcomingCalendarEntries(String vehicleTag, Object body) {
        return commandPost(vehicleTag, "upcoming_calendar_entries", body);
    }

    public String commandWindowControl(String vehicleTag, Object body) {
        return commandPost(vehicleTag, "window_control", body);
    }

    /**
     * GET https://api.tessie.com/{vin}/fleet_telemetry_config
     */
    public String tessieGetTelemetryConfig(String vin) {
        return tessieGet("/" + vin + "/fleet_telemetry_config", null);
    } // citeturn5view0

    /**
     * POST https://api.tessie.com/{vin}/fleet_telemetry_config
     */
    public String tessieSetTelemetryConfig(String vin, Object body) {
        return tessiePost("/" + vin + "/fleet_telemetry_config", body);
    } // citeturn5view0

    // ==================================================================================
    //                                     TESSIE API
    //  Reference: https://developer.tessie.com/reference/quick-start and linked pages
    //  Paths generally follow: /vehicles, /{vin}/state, /{vin}/command/{cmd}, etc.
    // ==================================================================================

    // -------- Tesla Fleet Telemetry via Tessie --------

    /**
     * DELETE https://api.tessie.com/{vin}/fleet_telemetry_config
     */
    public String tessieDeleteTelemetryConfig(String vin) {
        return tessieDelete("/" + vin + "/fleet_telemetry_config");
    } // citeturn5view0

    /**
     * GET https://api.tessie.com/vehicles
     */
    public String tessieGetVehicles() {
        return tessieGet("/vehicles", null);
    } // citeturn3view0

    /**
     * GET https://api.tessie.com/{vin}/state
     */
    public String tessieGetVehicle(String vin) {
        return tessieGet("/" + vin + "/state", null);
    } // citeturn2view0

    public String tessieGetVehicleNoCache(String vin) {
        return tessieGet("/" + vin + "/state", Map.of("use_cache", "false"));
    }

    // -------- Vehicle Data --------

    /**
     * GET https://api.tessie.com/{vin}/states?start=...&end=...
     */
    public String tessieGetHistoricalStates(String vin, Map<String, String> query) {
        return tessieGet("/" + vin + "/states", query);
    }

    /**
     * GET https://api.tessie.com/{vin}/battery
     */
    public String tessieGetBattery(String vin) {
        return tessieGet("/" + vin + "/battery", null);
    }

    /**
     * GET https://api.tessie.com/{vin}/battery_health
     */
    public String tessieGetBatteryHealth(String vin) {
        return tessieGet("/" + vin + "/battery_health", null);
    }

    /**
     * GET https://api.tessie.com/{vin}/battery_health_measurements
     */
    public String tessieGetBatteryHealthMeasurements(String vin, Map<String, String> query) {
        return tessieGet("/" + vin + "/battery_health_measurements", query);
    }

    /**
     * GET https://api.tessie.com/{vin}/location
     */
    public String tessieGetLocation(String vin) {
        return tessieGet("/" + vin + "/location", null);
    }

    /**
     * GET https://api.tessie.com/{vin}/firmware_alerts
     */
    public String tessieGetFirmwareAlerts(String vin, Map<String, String> query) {
        return tessieGet("/" + vin + "/firmware_alerts", query);
    }

    /**
     * GET https://api.tessie.com/{vin}/map
     */
    public String tessieGetMap(String vin, Map<String, String> query) {
        return tessieGet("/" + vin + "/map", query);
    }

    /**
     * GET https://api.tessie.com/{vin}/consumption
     */
    public String tessieGetConsumption(String vin, Map<String, String> query) {
        return tessieGet("/" + vin + "/consumption", query);
    }

    /**
     * GET https://api.tessie.com/{vin}/weather
     */
    public String tessieGetWeather(String vin, Map<String, String> query) {
        return tessieGet("/" + vin + "/weather", query);
    }

    /**
     * GET https://api.tessie.com/{vin}/drives
     */
    public String tessieGetDrives(String vin, Map<String, String> query) {
        return tessieGet("/" + vin + "/drives", query);
    } // citeturn3view2

    /**
     * GET https://api.tessie.com/{vin}/drives/{id}/path
     */
    public String tessieGetDrivingPath(String vin, String driveId) {
        return tessieGet("/" + vin + "/drives/" + encode(driveId) + "/path", null);
    }

    /**
     * POST https://api.tessie.com/{vin}/drives/{id}/tag
     */
    public String tessieSetDriveTag(String vin, String driveId, Object body) {
        return tessiePost("/" + vin + "/drives/" + encode(driveId) + "/tag", body);
    }

    /**
     * GET https://api.tessie.com/{vin}/charges
     */
    public String tessieGetCharges(String vin, Map<String, String> query) {
        return tessieGet("/" + vin + "/charges", query);
    }

    /**
     * GET https://api.tessie.com/{vin}/charging_invoices
     */
    public String tessieGetAllChargingInvoices(String vin, Map<String, String> query) {
        return tessieGet("/" + vin + "/charging_invoices", query);
    }

    /**
     * POST https://api.tessie.com/{vin}/charges/{id}/cost
     */
    public String tessieSetChargeCost(String vin, String chargeId, Object body) {
        return tessiePost("/" + vin + "/charges/" + encode(chargeId) + "/cost", body);
    }

    /**
     * GET https://api.tessie.com/{vin}/idles
     */
    public String tessieGetIdles(String vin, Map<String, String> query) {
        return tessieGet("/" + vin + "/idles", query);
    }

    /**
     * GET https://api.tessie.com/{vin}/idles/last
     */
    public String tessieGetLastIdleState(String vin) {
        return tessieGet("/" + vin + "/idles/last", null);
    }

    /**
     * GET https://api.tessie.com/{vin}/tire_pressure
     */
    public String tessieGetTirePressure(String vin) {
        return tessieGet("/" + vin + "/tire_pressure", null);
    }

    /**
     * GET https://api.tessie.com/{vin}/status
     */
    public String tessieGetStatus(String vin) {
        return tessieGet("/" + vin + "/status", null);
    }

    /**
     * GET https://api.tessie.com/{vin}/license_plate
     */
    public String tessieGetLicensePlate(String vin) {
        return tessieGet("/" + vin + "/license_plate", null);
    }

    /**
     * POST https://api.tessie.com/{vin}/license_plate
     */
    public String tessieSetLicensePlate(String vin, Object body) {
        return tessiePost("/" + vin + "/license_plate", body);
    }

    // -------- Vehicle Commands (/{vin}/command/*) --------
    private String tessieCommand(String vin, String command, Object body) {
        return tessiePost("/" + vin + "/command/" + command, body == null ? "{}" : body);
    }

    public String tessieWake(String vin) {
        return tessieCommand(vin, "wake", "{}");
    } // citeturn1view0

    public String tessieLock(String vin) {
        return tessieCommand(vin, "lock", "{}");
    }

    public String tessieUnlock(String vin) {
        return tessieCommand(vin, "unlock", "{}");
    }

    public String tessieFrontTrunk(String vin) {
        return tessieCommand(vin, "front_trunk", "{}");
    }

    public String tessieRearTrunk(String vin) {
        return tessieCommand(vin, "rear_trunk", "{}");
    }

    public String tessieOpenTonneau(String vin) {
        return tessieCommand(vin, "open_tonneau", "{}");
    }

    public String tessieCloseTonneau(String vin) {
        return tessieCommand(vin, "close_tonneau", "{}");
    }

    public String tessieVentWindows(String vin) {
        return tessieCommand(vin, "vent_windows", "{}");
    }

    public String tessieCloseWindows(String vin) {
        return tessieCommand(vin, "close_windows", "{}");
    }

    public String tessieStartClimate(String vin) {
        return tessieCommand(vin, "start_climate", "{}");
    } // citeturn4view0

    public String tessieStopClimate(String vin) {
        return tessieCommand(vin, "stop_climate", "{}");
    }

    public String tessieSetTemperature(String vin, Object body) {
        return tessieCommand(vin, "set_temperature", body);
    }

    public String tessieSetSeatHeating(String vin, Object body) {
        return tessieCommand(vin, "set_seat_heating", body);
    }

    public String tessieSetSeatCooling(String vin, Object body) {
        return tessieCommand(vin, "set_seat_cooling", body);
    }

    public String tessieStartDefrost(String vin) {
        return tessieCommand(vin, "start_defrost", "{}");
    }

    public String tessieStopDefrost(String vin) {
        return tessieCommand(vin, "stop_defrost", "{}");
    }

    public String tessieStartSteeringWheelHeater(String vin) {
        return tessieCommand(vin, "start_steering_wheel_heater", "{}");
    }

    public String tessieStopSteeringWheelHeater(String vin) {
        return tessieCommand(vin, "stop_steering_wheel_heater", "{}");
    }

    public String tessieSetCabinOverheatProtection(String vin, Object body) {
        return tessieCommand(vin, "set_cabin_overheat_protection", body);
    }

    public String tessieSetCabinOverheatProtectionTemp(String vin, Object body) {
        return tessieCommand(vin, "set_cabin_overheat_protection_temp", body);
    }

    public String tessieSetBioDefenseMode(String vin, Object body) {
        return tessieCommand(vin, "set_bio_defense_mode", body);
    }

    public String tessieSetClimateKeeperMode(String vin, Object body) {
        return tessieCommand(vin, "set_climate_keeper_mode", body);
    }

    public String tessieStartCharging(String vin) {
        return tessieCommand(vin, "start_charging", "{}");
    }

    public String tessieStopCharging(String vin) {
        return tessieCommand(vin, "stop_charging", "{}");
    }

    public String tessieSetChargeLimit(String vin, Object body) {
        return tessieCommand(vin, "set_charge_limit", body);
    }

    public String tessieSetChargingAmps(String vin, Object body) {
        return tessieCommand(vin, "set_charging_amps", body);
    }

    public String tessieOpenChargePort(String vin) {
        return tessieCommand(vin, "open_charge_port", "{}");
    }

    public String tessieCloseChargePort(String vin) {
        return tessieCommand(vin, "close_charge_port", "{}");
    }

    public String tessieFlashLights(String vin) {
        return tessieCommand(vin, "flash_lights", "{}");
    }

    public String tessieHonk(String vin) {
        return tessieCommand(vin, "honk", "{}");
    }

    public String tessieTriggerHomeLink(String vin, Object body) {
        return tessieCommand(vin, "trigger_homelink", body);
    }

    public String tessieEnableKeylessDriving(String vin) {
        return tessieCommand(vin, "enable_keyless_driving", "{}");
    }

    public String tessieVentSunroof(String vin) {
        return tessieCommand(vin, "vent_sunroof", "{}");
    }

    public String tessieCloseSunroof(String vin) {
        return tessieCommand(vin, "close_sunroof", "{}");
    }

    public String tessieEnableSentryMode(String vin) {
        return tessieCommand(vin, "enable_sentry_mode", "{}");
    }

    public String tessieDisableSentryMode(String vin) {
        return tessieCommand(vin, "disable_sentry_mode", "{}");
    }

    public String tessieEnableValetMode(String vin, Object body) {
        return tessieCommand(vin, "enable_valet_mode", body);
    }

    public String tessieDisableValetMode(String vin) {
        return tessieCommand(vin, "disable_valet_mode", "{}");
    }

    public String tessieScheduleSoftwareUpdate(String vin, Object body) {
        return tessieCommand(vin, "schedule_software_update", body);
    }

    public String tessieCancelSoftwareUpdate(String vin) {
        return tessieCommand(vin, "cancel_software_update", "{}");
    }

    public String tessieSetScheduledCharging(String vin, Object body) {
        return tessieCommand(vin, "set_scheduled_charging", body);
    }

    public String tessieSetScheduledDeparture(String vin, Object body) {
        return tessieCommand(vin, "set_scheduled_departure", body);
    }

    public String tessieAddChargeSchedule(String vin, Object body) {
        return tessieCommand(vin, "add_charge_schedule", body);
    } // citeturn1view0

    public String tessieRemoveChargeSchedule(String vin, Object body) {
        return tessieCommand(vin, "remove_charge_schedule", body);
    }

    public String tessieAddPreconditionSchedule(String vin, Object body) {
        return tessieCommand(vin, "add_precondition_schedule", body);
    } // citeturn3view4

    public String tessieRemovePreconditionSchedule(String vin, Object body) {
        return tessieCommand(vin, "remove_precondition_schedule", body);
    }

    public String tessieShare(String vin, Object body) {
        return tessieCommand(vin, "share", body);
    }

    public String tessieBoombox(String vin, Object body) {
        return tessieCommand(vin, "boombox", body);
    }

    public String tessieSetSpeedLimit(String vin, Object body) {
        return tessieCommand(vin, "set_speed_limit", body);
    }

    public String tessieEnableSpeedLimit(String vin) {
        return tessieCommand(vin, "enable_speed_limit", "{}");
    }

    public String tessieDisableSpeedLimit(String vin) {
        return tessieCommand(vin, "disable_speed_limit", "{}");
    }

    public String tessieClearSpeedLimitPin(String vin) {
        return tessieCommand(vin, "clear_speed_limit_pin", "{}");
    }

    /**
     * GET https://api.tessie.com/{vin}/drivers
     */
    public String tessieGetDrivers(String vin) {
        return tessieGet("/" + vin + "/drivers", null);
    } // citeturn2view0

    /**
     * POST https://api.tessie.com/{vin}/drivers/delete
     */
    public String tessieDeleteDriver(String vin, Object body) {
        return tessiePost("/" + vin + "/drivers/delete", body);
    }

    // -------- Driver Management --------

    /**
     * POST https://api.tessie.com/{vin}/guest_mode/enable
     */
    public String tessieEnableGuestMode(String vin, Object body) {
        return tessiePost("/" + vin + "/guest_mode/enable", body);
    }

    /**
     * POST https://api.tessie.com/{vin}/guest_mode/disable
     */
    public String tessieDisableGuestMode(String vin) {
        return tessiePost("/" + vin + "/guest_mode/disable", "{}");
    }

    /**
     * GET https://api.tessie.com/{vin}/invitations
     */
    public String tessieGetInvitations(String vin) {
        return tessieGet("/" + vin + "/invitations", null);
    }

    /**
     * POST https://api.tessie.com/{vin}/invitations
     */
    public String tessieCreateInvitation(String vin, Object body) {
        return tessiePost("/" + vin + "/invitations", body);
    }

    /**
     * POST https://api.tessie.com/{vin}/invitations/{id}/revoke
     */
    public String tessieRevokeInvitation(String vin, String invitationId) {
        return tessiePost("/" + vin + "/invitations/" + encode(invitationId) + "/revoke", "{}");
    }

    public enum Region {
        NA("https://fleet-api.prd.na.vn.cloud.tesla.com"),
        EU("https://fleet-api.prd.eu.vn.cloud.tesla.com"),
        CN("https://fleet-api.prd.cn.vn.cloud.tesla.cn");
        public final String baseUrl;

        Region(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }

    public static final class Builder {
        private String accessToken;
        private String baseUrl; // overrides region if set
        private Region region = Region.NA;
        private boolean autoDetectRegion = false;
        private Duration timeout = Duration.ofSeconds(30);
        private String userAgent = "TeslaFleetApiClient/1.0";
        private boolean logRequests = false;
        private boolean useCommandsProxy = false;
        private String commandsProxyBaseUrl; // e.g., https://your-proxy.example.com
        private int maxRetries = 3;

        public Builder accessToken(String token) {
            this.accessToken = token;
            return this;
        }

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder region(Region region) {
            this.region = region;
            return this;
        }

        public Builder autoDetectRegion() {
            this.autoDetectRegion = true;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder userAgent(String ua) {
            this.userAgent = ua;
            return this;
        }

        public Builder logRequests(boolean log) {
            this.logRequests = log;
            return this;
        }

        public Builder useCommandsProxy(String proxyBaseUrl) {
            this.useCommandsProxy = true;
            this.commandsProxyBaseUrl = proxyBaseUrl;
            return this;
        }

        public Builder maxRetries(int n) {
            this.maxRetries = Math.max(0, n);
            return this;
        }

        public FleetApi build() {
            Objects.requireNonNull(accessToken, "accessToken required");
            String resolvedBase = (baseUrl != null) ? baseUrl : region.baseUrl;
            return new FleetApi(accessToken, resolvedBase, region, autoDetectRegion, timeout, userAgent, logRequests, useCommandsProxy, commandsProxyBaseUrl, maxRetries);
        }
    }
}