package com.jtdev.teslaautomaticpreconditioning.service;

import com.google.api.services.calendar.model.Event;
import com.jtdev.teslaautomaticpreconditioning.entity.CalendarPreConditionLinkEntity;
import com.jtdev.teslaautomaticpreconditioning.entity.PreconditioningStatus;
import com.jtdev.teslaautomaticpreconditioning.fleetapi.VehicleData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

/**
 * Manages smart reuse and comparison of preconditioning schedules
 */
@Slf4j
@Component
public class SmartScheduleManager {
    
    private static final int TOLERANCE_MINUTES = 5; // ±5 minutes tolerance for reuse

    public enum ScheduleMatchResult {
        EXACT_MATCH,        // Perfect match, can reuse
        TOLERANCE_MATCH,    // Within tolerance, can reuse
        NEEDS_MODIFICATION, // Close but needs small changes
        NO_MATCH           // Completely different, needs new schedule
    }
    
    public static class ScheduleComparison {
        private final ScheduleMatchResult result;
        private final VehicleData.PreconditionSchedule existingSchedule;
        private final String reason;
        
        public ScheduleComparison(ScheduleMatchResult result, VehicleData.PreconditionSchedule existingSchedule, String reason) {
            this.result = result;
            this.existingSchedule = existingSchedule;
            this.reason = reason;
        }
        
        public ScheduleMatchResult getResult() { return result; }
        public VehicleData.PreconditionSchedule getExistingSchedule() { return existingSchedule; }
        public String getReason() { return reason; }
    }
    
    /**
     * Find the best matching existing preconditioning schedule for a desired time and day
     */
    public ScheduleComparison findBestMatch(List<VehicleData.PreconditionSchedule> existingSchedules, 
                                          int desiredPreconditioningTime, 
                                          DayOfWeek dayOfWeek,
                                          FleetApiService fleetApiService) {
        
        if (existingSchedules == null || existingSchedules.isEmpty()) {
            return new ScheduleComparison(ScheduleMatchResult.NO_MATCH, null, "No existing schedules");
        }
        
        // Filter to one-time schedules only (we manage these)
        List<VehicleData.PreconditionSchedule> oneTimeSchedules = existingSchedules.stream()
                .filter(VehicleData.PreconditionSchedule::getOne_time)
                .filter(VehicleData.PreconditionSchedule::getEnabled)
                .toList();
        
        VehicleData.PreconditionSchedule bestMatch = null;
        ScheduleMatchResult bestResult = ScheduleMatchResult.NO_MATCH;
        String bestReason = "";
        int bestTimeDifference = Integer.MAX_VALUE;
        
        for (VehicleData.PreconditionSchedule schedule : oneTimeSchedules) {
            // Check if this schedule matches the day of week
            Set<DayOfWeek> scheduleDays = fleetApiService.decodeDaysOfWeek(schedule.getDays_of_week());
            if (!scheduleDays.contains(dayOfWeek)) {
                continue; // Skip schedules for different days
            }
            
            int timeDifference = Math.abs(schedule.getPrecondition_time() - desiredPreconditioningTime);
            
            // Exact match
            if (timeDifference == 0) {
                return new ScheduleComparison(ScheduleMatchResult.EXACT_MATCH, schedule, 
                        "Exact time match: " + schedule.getPrecondition_time());
            }
            
            // Within tolerance
            if (timeDifference <= TOLERANCE_MINUTES && timeDifference < bestTimeDifference) {
                bestMatch = schedule;
                bestResult = ScheduleMatchResult.TOLERANCE_MATCH;
                bestReason = String.format("Within tolerance: existing %d vs desired %d (diff: %d min)", 
                        schedule.getPrecondition_time(), desiredPreconditioningTime, timeDifference);
                bestTimeDifference = timeDifference;
            }
            
            // Potential modification candidate (within 15 minutes)
            else if (timeDifference <= 15 && bestResult == ScheduleMatchResult.NO_MATCH) {
                bestMatch = schedule;
                bestResult = ScheduleMatchResult.NEEDS_MODIFICATION;
                bestReason = String.format("Could modify: existing %d vs desired %d (diff: %d min)", 
                        schedule.getPrecondition_time(), desiredPreconditioningTime, timeDifference);
            }
        }
        
        return new ScheduleComparison(bestResult, bestMatch, bestReason);
    }
    
    /**
     * Check if an existing schedule is relevant to any of our managed calendar events
     */
    public boolean isScheduleRelevant(VehicleData.PreconditionSchedule schedule, 
                                    List<CalendarPreConditionLinkEntity> managedEntities,
                                    List<Event> currentEvents,
                                    FleetApiService fleetApiService,
                                    com.jtdev.teslaautomaticpreconditioning.repository.CalendarPreConditionLinkRepository repository) {
        
        // Check if this schedule corresponds to any of our managed entities - always keep these
        boolean isManaged = managedEntities.stream()
                .anyMatch(entity -> entity.getPreconditionId() > 0 && 
                         entity.getPreconditionId() == schedule.getId());
        
        if (isManaged) {
            log.debug("Schedule {} is managed by our application, keeping it", schedule.getId());
            return true;
        }
        
        // PROTECTION: Check if this might be a recently created schedule awaiting verification
        boolean scheduleInPast = !schedule.getEnabled() || !schedule.getOne_time() || (Instant.now().atZone(ZoneId.of("America/New_York")).getDayOfWeek() != DayOfWeek.SUNDAY && schedule.getDays_of_week() < fleetApiService.encodeDaysOfWeek(Set.of(Instant.now().atZone(ZoneId.of("America/New_York")).getDayOfWeek())) || (schedule.getDays_of_week() == fleetApiService.encodeDaysOfWeek(Set.of(Instant.now().atZone(ZoneId.of("America/New_York")).getDayOfWeek())) && schedule.getPrecondition_time() < Instant.now().atZone(ZoneId.of("America/New_York")).getHour() * 60 + Instant.now().atZone(ZoneId.of("America/New_York")).getMinute()));
        if (!scheduleInPast) {
            log.debug("Schedule {} protected", schedule.getId());
            return true;
        }
        
        // AGGRESSIVE CLEANUP: Remove ALL other schedules (one-time and recurring)
        // This assumes our application has full ownership of preconditioning schedules
        log.debug("Schedule {} is from other sources - marking for deletion. Type: {}, Time: {}, Days: {}", 
                schedule.getId(), 
                schedule.getOne_time() ? "one-time" : "recurring",
                schedule.getPrecondition_time(), 
                schedule.getDays_of_week());
        return false;
    }
    
    /**
     * Check if a schedule might be a recently created one awaiting verification
     * Protects against deleting schedules and completes verification if match found
     */
    public boolean isPotentiallyPendingSchedule(VehicleData.PreconditionSchedule schedule, 
                                               List<CalendarPreConditionLinkEntity> managedEntities,
                                               FleetApiService fleetApiService,
                                               com.jtdev.teslaautomaticpreconditioning.repository.CalendarPreConditionLinkRepository repository) {
        
        // Only protect one-time enabled schedules (our type)
        if (!schedule.getEnabled() || !schedule.getOne_time()) {
            return false;
        }
        
        // Check if any PENDING entities could potentially own this schedule
        Set<DayOfWeek> scheduleDays = fleetApiService.decodeDaysOfWeek(schedule.getDays_of_week());
        
        return managedEntities.stream()
                .filter(entity -> entity.getStatus() == PreconditioningStatus.PENDING)
                .filter(entity -> entity.getPreconditionId() == 0) // No ID assigned yet
                .anyMatch(entity -> {
                    // Check if stored parameters match this schedule (within tolerance)
                    if (entity.getStoredPreconditionTime() != null && entity.getStoredDayOfWeek() != null) {
                        try {
                            DayOfWeek entityDay = DayOfWeek.valueOf(entity.getStoredDayOfWeek().toUpperCase());
                            boolean timeMatches = Math.abs(schedule.getPrecondition_time() - entity.getStoredPreconditionTime()) <= TOLERANCE_MINUTES;
                            boolean dayMatches = scheduleDays.contains(entityDay);
                            
                            if (timeMatches && dayMatches) {
                                log.info("Schedule {} matches pending entity {} - completing verification during cleanup", 
                                         schedule.getId(), entity.getCalendarId());
                                
                                // Complete verification immediately since we have fresh data
                                entity.setPreconditionId(schedule.getId());
                                entity.setStatus(PreconditioningStatus.ACTIVE);
                                entity.setLastVerifiedTimestamp(System.currentTimeMillis());
                                repository.save(entity);
                                
                                log.info("✅ Verification completed during cleanup for entity {}", entity.getCalendarId());
                                return true;
                            }
                        } catch (Exception e) {
                            log.debug("Error checking entity {} against schedule {}: {}", 
                                     entity.getCalendarId(), schedule.getId(), e.getMessage());
                        }
                    }
                    return false;
                });
    }
    
    /**
     * Calculate the desired preconditioning time for an event
     */
    public int calculateDesiredPreconditioningTime(Event event, int minutesToDestination, int bufferMinutes) {
        ZonedDateTime eventTime = Instant.ofEpochMilli(event.getStart().getDateTime().getValue())
                .atZone(ZoneId.of("America/New_York"));
        int eventMinutesFromMidnight = eventTime.getHour() * 60 + eventTime.getMinute();
        return eventMinutesFromMidnight - minutesToDestination - bufferMinutes;
    }
    
    /**
     * Check if stored preconditioning parameters match desired parameters (avoids vehicle query)
     */
    public boolean needsUpdate(CalendarPreConditionLinkEntity entity, 
                              int desiredPreconditioningTime, 
                              DayOfWeek dayOfWeek,
                              double latitude, 
                              double longitude) {
        
        // Always need update if not ACTIVE
        if (entity.getStatus() != PreconditioningStatus.ACTIVE) {
            return true;
        }
        
        // Check if stored parameters match desired ones
        boolean timeMatches = entity.getStoredPreconditionTime() != null && 
                             Math.abs(entity.getStoredPreconditionTime() - desiredPreconditioningTime) <= TOLERANCE_MINUTES;
        
        boolean dayMatches = dayOfWeek.name().equalsIgnoreCase(entity.getStoredDayOfWeek());
        
        boolean locationMatches = entity.getStoredLatitude() != null && 
                                 entity.getStoredLongitude() != null &&
                                 Math.abs(entity.getStoredLatitude() - latitude) < 0.001 && // ~100m tolerance
                                 Math.abs(entity.getStoredLongitude() - longitude) < 0.001;
        
        // Check if verification is recent (within 24 hours)
        boolean recentlyVerified = entity.getLastVerifiedTimestamp() != null &&
                                  (System.currentTimeMillis() - entity.getLastVerifiedTimestamp()) < (24 * 60 * 60 * 1000);
        
        // Need update if any parameter doesn't match or if not recently verified
        return !(timeMatches && dayMatches && locationMatches && recentlyVerified);
    }
    
    /**
     * Update stored parameters after successful preconditioning creation/verification
     */
    public void updateStoredParameters(CalendarPreConditionLinkEntity entity,
                                     int preconditioningTime,
                                     DayOfWeek dayOfWeek,
                                     double latitude,
                                     double longitude) {
        entity.setStoredPreconditionTime(preconditioningTime);
        entity.setStoredDayOfWeek(dayOfWeek.name());
        entity.setStoredLatitude(latitude);
        entity.setStoredLongitude(longitude);
        entity.setLastVerifiedTimestamp(System.currentTimeMillis());
    }
}
