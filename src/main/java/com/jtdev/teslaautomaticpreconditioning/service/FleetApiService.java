package com.jtdev.teslaautomaticpreconditioning.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jtdev.teslaautomaticpreconditioning.fleetapi.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class FleetApiService {
    
    /**
     * Result of wake operation indicating if vehicle is awake and if wake was from cache
     */
    public static class WakeResult {
        private final boolean awake;
        private final boolean fromCache;
        
        public WakeResult(boolean awake, boolean fromCache) {
            this.awake = awake;
            this.fromCache = fromCache;
        }
        
        public boolean isAwake() { return awake; }
        public boolean isFromCache() { return fromCache; }
    }
    
    // Cache for vehicle wake state - VIN -> timestamp when vehicle was confirmed awake
    private final Map<String, Long> vehicleWakeCache = new ConcurrentHashMap<>();
    private static final long WAKE_CACHE_DURATION_MS = 10 * 60 * 1000L; // 10 minutes

    @Autowired
    private FleetApi fleetApi;
    
    @Autowired
    private IntelligentVehicleDataCache vehicleDataCache;

    /**
     * Convert Tesla's day-of-week bitmask into a Java Set of DayOfWeek values.
     * <p>
     * Tesla mapping:
     * 1 = Sunday
     * 2 = Monday
     * 4 = Tuesday
     * 8 = Wednesday
     * 16 = Thursday
     * 32 = Friday
     * 64 = Saturday
     */
    public Set<DayOfWeek> decodeDaysOfWeek(int mask) {
        EnumSet<DayOfWeek> out = EnumSet.noneOf(DayOfWeek.class);
        if ((mask & 1) != 0) out.add(DayOfWeek.SUNDAY);
        if ((mask & 2) != 0) out.add(DayOfWeek.MONDAY);
        if ((mask & 4) != 0) out.add(DayOfWeek.TUESDAY);
        if ((mask & 8) != 0) out.add(DayOfWeek.WEDNESDAY);
        if ((mask & 16) != 0) out.add(DayOfWeek.THURSDAY);
        if ((mask & 32) != 0) out.add(DayOfWeek.FRIDAY);
        if ((mask & 64) != 0) out.add(DayOfWeek.SATURDAY);
        return out;
    }

    public int encodeDaysOfWeek(Set<DayOfWeek> days) {
        int mask = 0;
        if (days.contains(DayOfWeek.SUNDAY)) mask |= 1;
        if (days.contains(DayOfWeek.MONDAY)) mask |= 2;
        if (days.contains(DayOfWeek.TUESDAY)) mask |= 4;
        if (days.contains(DayOfWeek.WEDNESDAY)) mask |= 8;
        if (days.contains(DayOfWeek.THURSDAY)) mask |= 16;
        if (days.contains(DayOfWeek.FRIDAY)) mask |= 32;
        if (days.contains(DayOfWeek.SATURDAY)) mask |= 64;
        return mask;
    }

    public VehicleData getVehicleData(String vin) {
        return getVehicleDataWithEndpoints(vin, "preconditioning_schedule_data;drive_state;location_data", true);
    }

    public VehicleData getVehicleDataNoCache(String vin) throws InterruptedException {
        forceVehicleDataRefresh(vin);
        Thread.sleep(10000);
        return getVehicleDataWithEndpoints(vin, "preconditioning_schedule_data;drive_state;location_data", false);
    }

    public void forceVehicleDataRefresh(String vin) {
        log.info("Forcing vehicle data refresh for VIN {}", vin);
        fleetApi.forceRefresh(vin);
    }
    /**
     * Get specific vehicle data endpoints with optional caching
     * @param vin Vehicle identification number
     * @param endpoints Semicolon-separated list of endpoints (e.g., "drive_state;climate_state;preconditioning_schedule_data")
     * @param useCache Whether to use Tessie's cache
     */
    public VehicleData getVehicleDataWithEndpoints(String vin, String endpoints, boolean useCache) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            
            // Build query parameters for Tesla API proxy
            Map<String, String> queryParams = new HashMap<>();
            queryParams.put("endpoints", endpoints);
            if (!useCache) {
                queryParams.put("use_cache", "false");
            }
            
            // Use Tesla API proxy via Tessie with proper endpoints
            log.debug("Requesting vehicle data for VIN {} with endpoints: {} (useCache: {})", vin, endpoints, useCache);
            String vehicleDataJson = fleetApi.vehicleData(vin, queryParams);
            
            // Log response details for debugging
            log.debug("Vehicle data response for VIN {}: {} characters", vin, vehicleDataJson.length());
            if (log.isDebugEnabled()) {
                // Log if specific sections are present in raw JSON
                log.debug("Raw JSON contains preconditioning_schedule_data: {}", vehicleDataJson.contains("preconditioning_schedule_data"));
                log.debug("Raw JSON contains drive_state: {}", vehicleDataJson.contains("drive_state"));
                log.debug("Raw JSON contains climate_state: {}", vehicleDataJson.contains("climate_state"));
                
                // Log first 1000 chars of response for analysis
                String preview = vehicleDataJson.length() > 1000 ? 
                        vehicleDataJson.substring(0, 1000) + "..." : vehicleDataJson;
                log.debug("Vehicle data JSON for VIN {}: {}", vin, preview);
            }
            
            // Parse using wrapper class to handle "response" object
            TeslaApiResponse apiResponse = mapper.readValue(vehicleDataJson, TeslaApiResponse.class);
            VehicleData parsedData = apiResponse.getResponse();
            
            if (parsedData == null) {
                log.error("Tesla API response wrapper contains null vehicle data for VIN {}", vin);
                throw new RuntimeException("Invalid API response structure - no vehicle data in response wrapper");
            }
            
            log.debug("Successfully parsed VehicleData for VIN {}", vin);
            return parsedData;
        } catch (Exception e) {
            log.error("Failed to parse vehicle data for VIN {} with endpoints {}: {}", vin, endpoints, e.getMessage());
            throw new RuntimeException("Failed to parse vehicle data for endpoints: " + endpoints, e);
        }
    }
    
    /**
     * Intelligent vehicle data retrieval with caching and wake optimization
     * @param vin Vehicle identification number
     * @param operationType Type of operation (determines wake requirements)
     * @param hasUrgentDeparture Whether this VIN has departures within 1 hour
     * @param endpoints Specific data endpoints needed
     * @return VehicleData object (from cache or fresh fetch)
     */
    public VehicleData getIntelligentVehicleData(String vin, VehicleOperationType operationType, 
                                               boolean hasUrgentDeparture, String endpoints) {
        
        // Check if we can use cached data
        if (!vehicleDataCache.shouldFetchFreshData(vin, hasUrgentDeparture)) {
            VehicleData cachedData = vehicleDataCache.getCachedData(vin);
            if (cachedData != null) {
                log.info("✅ Using cached vehicle data for VIN {} (operation: {})", vin, operationType);
                return cachedData;
            }
        }
        
        // Need fresh data - determine if wake is required
        boolean needsWake = vehicleDataCache.needsWakeForOperation(vin, operationType, hasUrgentDeparture);
        
        if (needsWake) {
            log.debug("Waking vehicle {} for operation {} (fresh data required)", vin, operationType);
            if (!delayedWakeRetry(2, vin)) {
                log.error("Failed to wake vehicle {} for operation {}", vin, operationType);
                // Return cached data if available as fallback
                VehicleData fallbackData = vehicleDataCache.getCachedData(vin);
                if (fallbackData != null) {
                    log.info("Using stale cached data as fallback for VIN {}", vin);
                    return fallbackData;
                }
                throw new RuntimeException("Failed to wake vehicle and no cached data available");
            }
        } else {
            log.info("⚡ Skipping wake for VIN {} - read-only operation with fresh cached data", vin);
        }
        
        // Fetch fresh data
        log.info("🔄 Fetching fresh vehicle data for VIN {} (operation: {}, endpoints: {})", vin, operationType, endpoints);
        VehicleData freshData = getVehicleDataWithEndpoints(vin, endpoints, false);
        
        // Cache the fresh data
        vehicleDataCache.cacheVehicleData(vin, freshData, hasUrgentDeparture);
        
        return freshData;
    }
    
    /**
     * Get vehicle data with climate state for weather operations
     */
    public VehicleData getVehicleDataWithClimate(String vin) {
        return getVehicleDataWithEndpoints(vin, "climate_state;preconditioning_schedule_data", false);
    }

    public boolean addPreconditioningEntry(String vin, AddPreconditioningScheduleBody addPreconditioningScheduleBody) {
        try {
            log.info("=== ADD PRECONDITIONING ENTRY START ===");
            log.info("VIN: {}", vin);
            
            ObjectMapper mapper = new ObjectMapper();
            String body = mapper.writeValueAsString(addPreconditioningScheduleBody);
            log.info("Request body: {}", body);
            log.info("Target API: tessie/{}/command/add_precondition_schedule", vin);
            
            String s = fleetApi.commandAddPreconditionSchedule(vin, body);
            log.info("Raw API response: {}", s);
            log.info("Response contains 'true': {}", s.contains("true"));
            
            if (!s.contains("true")) {
                log.warn("Failed to add preconditioning entry for vin: {}", vin);
                log.warn("response: {}", s);
                log.info("=== ADD PRECONDITIONING ENTRY END (FAILED) ===");
                return false;
            }
            
            log.info("Successfully added preconditioning entry for VIN: {}", vin);
            
            // Invalidate cache after successful addition since we don't know the new schedule ID
            vehicleDataCache.clearCache(vin);
            log.info("🔄 Invalidated cache for VIN {} after successful schedule addition", vin);
            
            log.info("=== ADD PRECONDITIONING ENTRY END (SUCCESS) ===");
            return true;
        } catch (Exception e) {
            log.error("=== ADD PRECONDITIONING ENTRY END (EXCEPTION) ===");
            log.error("Failed to add preconditioning entry for VIN {}: {}", vin, e.getMessage(), e);
            return false;
        }
    }

    public boolean deletePreconditioningEntry(String vin, RemovePreconditionSchedule removePreconditionSchedule) {
        try {
            log.info("=== DELETE PRECONDITIONING ENTRY START ===");
            log.info("VIN: {}", vin);
            log.info("Schedule ID to delete: {}", removePreconditionSchedule.getId());
            
            ObjectMapper mapper = new ObjectMapper();
            String requestBody = mapper.writeValueAsString(removePreconditionSchedule);
            log.info("Request body: {}", requestBody);
            log.info("Target API: tessie/{}/command/remove_precondition_schedule", vin);
            
            String s = fleetApi.commandRemovePreconditionSchedule(vin, requestBody);
            log.info("Raw API response: {}", s);
            log.info("Response contains 'true': {}", s.contains("true"));
            
            if (!s.contains("true")) {
                log.warn("Failed to delete preconditioning entry {} for vin: {}", removePreconditionSchedule.getId(), vin);
                log.warn("response: {}", s);
                // Check if failure is due to non-existent schedule (this might be OK)
                if (s.contains("not_found") || s.contains("invalid") || s.contains("does not exist")) {
                    log.info("Schedule {} may have already been deleted or never existed, treating as success", removePreconditionSchedule.getId());
                    
                    // Update cached data to reflect that schedule doesn't exist
                    vehicleDataCache.updateCacheAfterScheduleRemoval(vin, removePreconditionSchedule.getId());
                    log.info("🗑️ Updated cache for VIN {} after confirming schedule {} doesn't exist", vin, removePreconditionSchedule.getId());
                    
                    log.info("=== DELETE PRECONDITIONING ENTRY END (SUCCESS - ALREADY DELETED) ===");
                    return true; // Treat as success since the goal (schedule removed) is achieved
                }
                log.info("=== DELETE PRECONDITIONING ENTRY END (FAILED) ===");
                return false;
            }
            log.info("Successfully deleted preconditioning entry {} for VIN: {}", removePreconditionSchedule.getId(), vin);
            
            // Update cached data to reflect the successful removal
            vehicleDataCache.updateCacheAfterScheduleRemoval(vin, removePreconditionSchedule.getId());
            log.info("🗑️ Updated cache for VIN {} after successful schedule {} removal", vin, removePreconditionSchedule.getId());
            
            log.info("=== DELETE PRECONDITIONING ENTRY END (SUCCESS) ===");
            return true;
        } catch (Exception e) {
            log.error("=== DELETE PRECONDITIONING ENTRY END (EXCEPTION) ===");
            log.error("Failed to delete preconditioning entry {} for VIN {}: {}", removePreconditionSchedule.getId(), vin, e.getMessage(), e);
            return false;
        }
    }

    public boolean startMaxDefrost(String vin) {
        String s = fleetApi.tessieStartDefrost(vin);
        if (!s.contains("true")) {
            log.info("Failed to start defrost for vin: {}", vin);
            log.info("response: {}", s);
        }
        return s.contains("true");
    }

    public boolean wakeVehicle(String vin) {
        log.info("=== WAKE VEHICLE START ===");
        log.info("VIN: {}", vin);
        log.info("Target API: /api/1/vehicles/{}/wake_up", vin);
        
        String s = fleetApi.vehiclesWakeUp(vin);
        log.info("Raw API response: {}", s);
        log.info("Response contains 'online': {}", s.contains("online"));
        
        if (!s.contains("online")) {
            log.warn("Failed to wake vehicle for vin: {}", vin);
            log.warn("response: {}", s);
            log.info("=== WAKE VEHICLE END (FAILED) ===");
            return false;
        }
        log.info("Successfully woke vehicle for VIN: {}", vin);
        log.info("=== WAKE VEHICLE END (SUCCESS) ===");
        return true;
    }

    public boolean delayedWakeRetry(Integer maxAttempts, String vin) {
        return delayedWakeRetryWithCacheInfo(maxAttempts, vin).isAwake();
    }
    
    /**
     * Wake retry with cache information
     * @return WakeResult indicating if vehicle is awake and whether wake was from cache
     */
    public WakeResult delayedWakeRetryWithCacheInfo(Integer maxAttempts, String vin) {
        // Check if vehicle is already awake (cached within 10 minutes)
        Long lastWakeTime = vehicleWakeCache.get(vin);
        if (lastWakeTime != null && (System.currentTimeMillis() - lastWakeTime) < WAKE_CACHE_DURATION_MS) {
            log.debug("Vehicle {} is cached as awake, skipping wake attempt", vin);
            return new WakeResult(true, true);  // awake=true, fromCache=true
        }
        
        if (maxAttempts > 0) {
            log.info("Delayed wake retry attempts for {} remaining: {}", vin, maxAttempts);
            try {
                if (wakeVehicle(vin)) {
                    // Cache successful wake state only
                    vehicleWakeCache.put(vin, System.currentTimeMillis());
                    log.debug("Vehicle {} successfully woken and cached", vin);
                    return new WakeResult(true, false);  // awake=true, fromCache=false
                } else {
                    Thread.sleep(1000L * 30);
                    return delayedWakeRetryWithCacheInfo(maxAttempts - 1, vin);
                }
            } catch (InterruptedException e) {
                log.error("Delayed wake retry interrupted", e);
                return delayedWakeRetryWithCacheInfo(maxAttempts - 1, vin);
            }
        }
        return new WakeResult(false, false);  // awake=false, fromCache=false
    }
    
    /**
     * Clear wake cache for a specific VIN (call when vehicle is known to be asleep)
     */
    public void clearWakeCache(String vin) {
        vehicleWakeCache.remove(vin);
        log.debug("Cleared wake cache for VIN {}", vin);
    }
    
    /**
     * Clean up expired wake cache entries
     */
    public void cleanupWakeCache() {
        long cutoffTime = System.currentTimeMillis() - WAKE_CACHE_DURATION_MS;
        vehicleWakeCache.entrySet().removeIf(entry -> entry.getValue() < cutoffTime);
    }
}
