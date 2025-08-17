package com.jtdev.teslaautomaticpreconditioning.service;

import com.jtdev.teslaautomaticpreconditioning.fleetapi.VehicleData;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lazy-loading map implementation that only fetches vehicle preconditioning data when accessed.
 * This avoids unnecessary API calls when the schedule data isn't actually needed.
 */
@Slf4j
public class LazyVehicleDataMap extends AbstractMap<String, List<VehicleData.PreconditionSchedule>> {
    
    private final Set<String> vins;
    private final FleetApiService fleetApiService;
    private final VehicleApiUsageTracker apiUsageTracker;
    private final Map<String, List<VehicleData.PreconditionSchedule>> cache = new ConcurrentHashMap<>();
    private final Map<String, Long> fetchTimestamps = new ConcurrentHashMap<>();
    private final Set<String> fetchAttempted = ConcurrentHashMap.newKeySet();
    private final Set<String> dataAccessed = ConcurrentHashMap.newKeySet();
    
    private static final long CACHE_EXPIRY_MS = 10 * 60 * 1000; // 10 minutes
    
    public LazyVehicleDataMap(Set<String> vins, FleetApiService fleetApiService, VehicleApiUsageTracker apiUsageTracker) {
        this.vins = new HashSet<>(vins);
        this.fleetApiService = fleetApiService;
        this.apiUsageTracker = apiUsageTracker;
    }
    
    @Override
    public List<VehicleData.PreconditionSchedule> get(Object key) {
        if (!(key instanceof String)) {
            return null;
        }
        
        String vin = (String) key;
        if (!vins.contains(vin)) {
            return null;
        }
        
        // Mark that this VIN's data was accessed
        dataAccessed.add(vin);
        
        // Check if we have cached data that's still valid
        Long fetchTime = fetchTimestamps.get(vin);
        if (fetchTime != null && (System.currentTimeMillis() - fetchTime) < CACHE_EXPIRY_MS) {
            return cache.get(vin);
        }
        
        // Check if we already attempted to fetch (avoid repeated failures)
        if (fetchAttempted.contains(vin)) {
            List<VehicleData.PreconditionSchedule> cached = cache.get(vin);
            if (cached != null) {
                return cached;
            }
        }
        
        // Lazy fetch the data
        return fetchVehicleData(vin);
    }
    
    private List<VehicleData.PreconditionSchedule> fetchVehicleData(String vin) {
        fetchAttempted.add(vin);
        
        if (!apiUsageTracker.canExecuteCommand(vin, VehicleApiUsageTracker.CommandType.GET_DATA)) {
            log.debug("Cannot get preconditioning schedule data for VIN {} due to API rate limits", vin);
            List<VehicleData.PreconditionSchedule> emptyList = new ArrayList<>();
            cache.put(vin, emptyList);
            return emptyList;
        }
        
        try {
            log.debug("Lazy-loading vehicle preconditioning data for VIN: {}", vin);
            fleetApiService.delayedWakeRetry(2, vin);
            VehicleData vehicleData = fleetApiService.getVehicleData(vin);
            apiUsageTracker.recordCommand(vin, VehicleApiUsageTracker.CommandType.GET_DATA);
            
            List<VehicleData.PreconditionSchedule> schedules = safeGetPreconditionSchedules(vehicleData);
            List<VehicleData.PreconditionSchedule> result = new ArrayList<>(schedules);
            
            // Cache the result
            cache.put(vin, result);
            fetchTimestamps.put(vin, System.currentTimeMillis());
            
            return result;
        } catch (Exception e) {
            log.error("Failed to fetch vehicle data for VIN {}: {}", vin, e.getMessage());
            List<VehicleData.PreconditionSchedule> emptyList = new ArrayList<>();
            cache.put(vin, emptyList);
            return emptyList;
        }
    }
    
    /**
     * Safely get preconditioning schedules from vehicle data with null safety checks
     */
    private List<VehicleData.PreconditionSchedule> safeGetPreconditionSchedules(VehicleData vehicleData) {
        if (vehicleData == null) {
            log.warn("VehicleData is null - Tesla API returned no data");
            return new ArrayList<>();
        }
        
        if (vehicleData.getPreconditioning_schedule_data() == null) {
            log.warn("Preconditioning_schedule_data is null - Tesla API didn't return this section");
            return new ArrayList<>();
        }
        
        if (vehicleData.getPreconditioning_schedule_data().getPrecondition_schedules() == null) {
            log.warn("Precondition_schedules list is null - empty schedule data section");
            return new ArrayList<>();
        }
        
        List<VehicleData.PreconditionSchedule> schedules = vehicleData.getPreconditioning_schedule_data().getPrecondition_schedules();
        log.debug("Found {} preconditioning schedules", schedules.size());
        return schedules;
    }
    
    @Override
    public boolean containsKey(Object key) {
        return key instanceof String && vins.contains(key);
    }
    
    @Override
    public Set<Entry<String, List<VehicleData.PreconditionSchedule>>> entrySet() {
        // This method is called by some operations - implement lazy loading for all VINs
        Set<Entry<String, List<VehicleData.PreconditionSchedule>>> entries = new HashSet<>();
        for (String vin : vins) {
            List<VehicleData.PreconditionSchedule> value = get(vin); // Triggers lazy loading
            entries.add(new AbstractMap.SimpleEntry<>(vin, value));
        }
        return entries;
    }
    
    @Override
    public Set<String> keySet() {
        return new HashSet<>(vins);
    }
    
    @Override
    public int size() {
        return vins.size();
    }
    
    /**
     * Check if data was accessed for a specific VIN (useful for cleanup optimization)
     */
    public boolean wasDataAccessed(String vin) {
        return dataAccessed.contains(vin);
    }
    
    /**
     * Get statistics about cache usage for monitoring
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("total_vins", vins.size());
        stats.put("cached_vins", cache.size());
        stats.put("fetch_attempted", fetchAttempted.size());
        stats.put("data_accessed", dataAccessed.size());
        stats.put("cache_hits", cache.entrySet().stream()
                .mapToInt(entry -> entry.getValue().size())
                .sum());
        return stats;
    }
}
