package com.jtdev.teslaautomaticpreconditioning.service;

import com.google.api.services.calendar.model.Event;
import com.jtdev.teslaautomaticpreconditioning.entity.CalendarPreConditionLinkEntity;
import com.jtdev.teslaautomaticpreconditioning.fleetapi.VehicleData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Intelligent caching system for vehicle data with urgency-based invalidation
 */
@Slf4j
@Component
public class IntelligentVehicleDataCache {
    
    private final Map<String, CachedVehicleData> cache = new ConcurrentHashMap<>();
    
    // Cache configuration
    private static final long STANDARD_CACHE_DURATION_MS = 4 * 60 * 60 * 1000L; // 4 hours
    private static final long URGENT_BYPASS_THRESHOLD_MS = 60 * 60 * 1000L;    // 1 hour
    private static final long CACHE_CLEANUP_INTERVAL_MS = 60 * 60 * 1000L;     // 1 hour cleanup
    
    private long lastCleanupTime = 0;
    
    /**
     * Cached vehicle data with metadata
     */
    private static class CachedVehicleData {
        private final VehicleData data;
        private final long timestamp;
        private final boolean wasUrgent;
        
        public CachedVehicleData(VehicleData data, boolean wasUrgent) {
            this.data = data;
            this.timestamp = System.currentTimeMillis();
            this.wasUrgent = wasUrgent;
        }
        
        public VehicleData getData() { return data; }
        public long getTimestamp() { return timestamp; }
        public boolean wasUrgent() { return wasUrgent; }
        public long getAgeMs() { return System.currentTimeMillis() - timestamp; }
    }
    
    /**
     * Determine if we should fetch fresh data for a VIN
     */
    public boolean shouldFetchFreshData(String vin, boolean hasUrgentDeparture) {
        performPeriodicCleanup();
        
        CachedVehicleData cached = cache.get(vin);
        
        // No cached data - fetch fresh
        if (cached == null) {
            log.info("No cached data for VIN {}, fetching fresh", vin);
            return true;
        }
        
        // If urgent departure detected, always fetch fresh data
        if (hasUrgentDeparture) {
            log.info("VIN {} has urgent departure within 1 hour, fetching fresh vehicle data", vin);
            return true;
        }
        
        // Check cache age
        long cacheAge = cached.getAgeMs();
        if (cacheAge > STANDARD_CACHE_DURATION_MS) {
            log.info("Cached data for VIN {} is {} hours old, fetching fresh", vin, cacheAge / (60 * 60 * 1000.0));
            return true;
        }
        
        log.info("Using cached vehicle data for VIN {} (age: {} minutes)", vin, cacheAge / (60 * 1000.0));
        return false;
    }
    
    /**
     * Check if a VIN has any departures within the next hour
     */
    private boolean hasUrgentDeparture(String vin, List<Event> allEvents, List<CalendarPreConditionLinkEntity> managedEntities) {
        long currentTime = System.currentTimeMillis();
        long oneHourFromNow = currentTime + URGENT_BYPASS_THRESHOLD_MS;
        
        // Check regular calendar events
        for (Event event : allEvents) {
            if (event.getStart() == null || event.getStart().getDateTime() == null) {
                continue;
            }
            
            long eventStartTime = event.getStart().getDateTime().getValue();
            if (eventStartTime <= oneHourFromNow && eventStartTime > currentTime) {
                // Check if this event is for this VIN
                if (isEventForVin(event, vin, managedEntities)) {
                    log.info("VIN {} has urgent departure: event {} starts in {} minutes", 
                            vin, event.getId(), (eventStartTime - currentTime) / (60 * 1000));
                    return true;
                }
            }
        }
        
        // Check return home events (synthetic events based on calendar event end times)
        for (Event event : allEvents) {
            if (event.getEnd() == null || event.getEnd().getDateTime() == null) {
                continue;
            }
            
            long returnHomeTime = event.getEnd().getDateTime().getValue();
            if (returnHomeTime <= oneHourFromNow && returnHomeTime > currentTime) {
                // Check if this return home event is for this VIN
                if (isEventForVin(event, vin, managedEntities)) {
                    log.info("VIN {} has urgent return home: event {} ends in {} minutes", 
                            vin, event.getId(), (returnHomeTime - currentTime) / (60 * 1000));
                    return true;
                }
            }
        }
        
        return false;
    }
    
    /**
     * Check if an event is associated with a specific VIN
     */
    private boolean isEventForVin(Event event, String vin, List<CalendarPreConditionLinkEntity> managedEntities) {
        return managedEntities.stream()
                .anyMatch(entity -> event.getId().equals(entity.getCalendarId()) && 
                                   vin.equals(entity.getVin()));
    }
    
    /**
     * Get cached vehicle data if available and valid
     */
    public VehicleData getCachedData(String vin) {
        CachedVehicleData cached = cache.get(vin);
        return cached != null ? cached.getData() : null;
    }
    
    /**
     * Get cache age in milliseconds for a VIN
     */
    public long getCacheAge(String vin) {
        CachedVehicleData cached = cache.get(vin);
        return cached != null ? cached.getAgeMs() : -1;
    }
    
    /**
     * Store vehicle data in cache
     */
    public void cacheVehicleData(String vin, VehicleData data, boolean wasUrgentFetch) {
        cache.put(vin, new CachedVehicleData(data, wasUrgentFetch));
        log.debug("Cached vehicle data for VIN {} (urgent: {})", vin, wasUrgentFetch);
    }
    
    /**
     * Determine if we need to wake vehicle for the intended operation
     */
    public boolean needsWakeForOperation(String vin, VehicleOperationType operationType, boolean hasUrgentDeparture) {
        
        // Always wake for commands that modify vehicle state
        if (operationType.requiresWake()) {
            log.debug("Operation {} requires wake for VIN {}", operationType, vin);
            return true;
        }
        
        // For read-only operations, check if we have fresh cached data
        if (!shouldFetchFreshData(vin, hasUrgentDeparture)) {
            log.debug("Using cached data for read-only operation {} on VIN {}, skipping wake", operationType, vin);
            return false;
        }
        
        // Need fresh data, so need to wake vehicle
        log.debug("Need fresh data for operation {} on VIN {}, wake required", operationType, vin);
        return true;
    }
    
    /**
     * Periodic cleanup of old cache entries
     */
    private void performPeriodicCleanup() {
        long currentTime = System.currentTimeMillis();
        
        if (currentTime - lastCleanupTime < CACHE_CLEANUP_INTERVAL_MS) {
            return;
        }
        
        int initialSize = cache.size();
        cache.entrySet().removeIf(entry -> {
            long age = entry.getValue().getAgeMs();
            // Remove entries older than 8 hours (2x standard cache duration)
            return age > (STANDARD_CACHE_DURATION_MS * 2);
        });
        
        lastCleanupTime = currentTime;
        int removedEntries = initialSize - cache.size();
        if (removedEntries > 0) {
            log.info("Cleaned up {} old vehicle data cache entries", removedEntries);
        }
    }
    
    /**
     * Get cache statistics for monitoring
     */
    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new ConcurrentHashMap<>();
        stats.put("cached_vins", cache.size());
        stats.put("oldest_entry_hours", cache.values().stream()
                .mapToLong(CachedVehicleData::getAgeMs)
                .max()
                .orElse(0) / (60 * 60 * 1000.0));
        stats.put("urgent_fetches", cache.values().stream()
                .mapToInt(cached -> cached.wasUrgent() ? 1 : 0)
                .sum());
        return stats;
    }
    
    /**
     * Clear cache for a specific VIN (useful for testing or manual refresh)
     */
    public void clearCache(String vin) {
        CachedVehicleData removed = cache.remove(vin);
        if (removed != null) {
            log.info("Cleared cached vehicle data for VIN {}", vin);
        }
    }
    
    /**
     * Update cached vehicle data after successful schedule addition
     */
    public void updateCacheAfterScheduleAdd(String vin, VehicleData.PreconditionSchedule addedSchedule) {
        CachedVehicleData cached = cache.get(vin);
        if (cached != null && cached.getData().getPreconditioning_schedule_data() != null) {
            List<VehicleData.PreconditionSchedule> schedules = 
                    cached.getData().getPreconditioning_schedule_data().getPrecondition_schedules();
            if (schedules != null) {
                schedules.add(addedSchedule);
                log.debug("Updated cached data for VIN {} - added schedule {}", vin, addedSchedule.getId());
            }
        }
    }
    
    /**
     * Update cached vehicle data after successful schedule removal
     */
    public void updateCacheAfterScheduleRemoval(String vin, Long removedScheduleId) {
        CachedVehicleData cached = cache.get(vin);
        if (cached != null && cached.getData().getPreconditioning_schedule_data() != null) {
            List<VehicleData.PreconditionSchedule> schedules = 
                    cached.getData().getPreconditioning_schedule_data().getPrecondition_schedules();
            if (schedules != null) {
                boolean removed = schedules.removeIf(schedule -> schedule.getId().equals(removedScheduleId));
                if (removed) {
                    log.debug("Updated cached data for VIN {} - removed schedule {}", vin, removedScheduleId);
                }
            }
        }
    }
    
    /**
     * Update cached vehicle location data (useful after location-based operations)
     */
    public void updateCacheLocation(String vin, double latitude, double longitude) {
        CachedVehicleData cached = cache.get(vin);
        if (cached != null && cached.getData().getDrive_state() != null) {
            cached.getData().getDrive_state().setLatitude(latitude);
            cached.getData().getDrive_state().setLongitude(longitude);
            cached.getData().getDrive_state().setTimestamp(System.currentTimeMillis());
            log.debug("Updated cached location for VIN {} to: {}, {}", vin, latitude, longitude);
        }
    }
    
    /**
     * Clear all cached data
     */
    public void clearAllCache() {
        int size = cache.size();
        cache.clear();
        log.info("Cleared all cached vehicle data ({} entries)", size);
    }
}
