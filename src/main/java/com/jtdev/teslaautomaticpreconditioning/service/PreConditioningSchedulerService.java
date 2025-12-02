package com.jtdev.teslaautomaticpreconditioning.service;


import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventAttendee;
import com.jtdev.teslaautomaticpreconditioning.entity.CalendarPreConditionLinkEntity;
import com.jtdev.teslaautomaticpreconditioning.entity.PreconditioningStatus;
import com.jtdev.teslaautomaticpreconditioning.entity.Telemetry;
import com.jtdev.teslaautomaticpreconditioning.fleetapi.AddPreconditioningScheduleBody;
import com.jtdev.teslaautomaticpreconditioning.fleetapi.RemovePreconditionSchedule;
import com.jtdev.teslaautomaticpreconditioning.fleetapi.VehicleData;
import com.jtdev.teslaautomaticpreconditioning.repository.CalendarPreConditionLinkRepository;
import jakarta.transaction.Transactional;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PreConditioningSchedulerService {

    @Autowired
    FleetApiService fleetApiService;

    @Autowired
    GoogleCalendarService googleCalendarService;

    @Value("${tesla.vin.csv}")
    private String vinCsv;

    @Value("${tesla.home.lat}")
    private double homeLatitude;

    @Value("${tesla.home.lon}")
    private double homeLongitude;

    @Value("${tesla.preconditioning.buffer.minutes}")
    private int preconditioningBufferMinutes;

    @Autowired
    private CalendarPreConditionLinkRepository calendarPreConditionLinkRepository;

    @Autowired
    private RoutesCalculationService routesCalculationService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private WeatherService weatherService;

    @Autowired
    private VehicleApiUsageTracker apiUsageTracker;
    
    @Autowired
    private SmartScheduleManager smartScheduleManager;
    
    @Autowired
    private IntelligentVehicleDataCache vehicleDataCache;

    @Value("${tesla.preconditioning.completion.delay.minutes:10}")
    private int completionDelayMinutes;

    @Value("${tesla.preconditioning.home.radius.miles:0.05}")
    private double homeRadiusMiles;
    
    @Value("${tesla.preconditioning.scheduling.out-of-metro-threshold-miles:100}")
    private double outOfMetroThresholdMiles;
    
    @Value("${tesla.preconditioning.scheduling.rescheduling.enabled:true}")
    private boolean reschedulingEnabled;
    
    @Value("${tesla.preconditioning.scheduling.rescheduling.movement-threshold-miles:25}")
    private double movementThresholdMiles;
    
    @Value("${tesla.preconditioning.scheduling.rescheduling.time-savings-threshold-minutes:30}")
    private int timeSavingsThresholdMinutes;
    
    @Value("${tesla.preconditioning.scheduling.rescheduling.minimum-time-remaining-hours:2}")
    private int minimumTimeRemainingHours;
    
    @Value("${app.notifications.sms.recipients:}")
    private String smsRecipients;

    @Getter
    private Map<String, Telemetry.Location> vinLocationMap = new HashMap<>();
    @Getter
    private Map<String, Double> vinOutsideTempMap = new HashMap<>();
    @Getter
    private Map<String, Double> vinInsideTempMap = new HashMap<>();
    
    // Vehicle name cache - maps VIN to friendly vehicle name (e.g., "Xena Warrior Princess")
    @Getter
    private final Map<String, String> vinVehicleNameMap = new ConcurrentHashMap<>();

    private Map<String, Long> vinDefrostMap = new HashMap<>();

    // Track recent telemetry timestamps to detect traveling vs stationary
    private final Map<String, List<Long>> vinTelemetryTimestamps = new ConcurrentHashMap<>();

    // Track recent telemetry locations with timestamps for distance calculation
    private final Map<String, List<LocationWithTimestamp>> vinTelemetryLocations = new ConcurrentHashMap<>();

    // Track previous travel state to detect transitions
    @Getter
    private final Map<String, Boolean> vinPreviousTravelState = new ConcurrentHashMap<>();

    // Track when we last received LOCATION data for each VIN (to detect stale location = parked)
    private final Map<String, Long> vinLastLocationUpdateTime = new ConcurrentHashMap<>();
    
    // Track when we last received ANY telemetry for each VIN
    private final Map<String, Long> vinLastTelemetryTime = new ConcurrentHashMap<>();

    /**
     * Simple data class to store location with timestamp
     */
    private static class LocationWithTimestamp {
        final double latitude;
        final double longitude;
        final long timestamp;

        LocationWithTimestamp(double latitude, double longitude, long timestamp) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.timestamp = timestamp;
        }
    }

    /**
     * Helper method to send SMS notifications to all configured recipients
     */
    private void sendSmsNotification(String message) {
        if (smsRecipients == null || smsRecipients.trim().isEmpty()) {
            log.debug("No SMS recipients configured, skipping notification");
            return;
        }
        
        String[] recipients = smsRecipients.split(",");
        for (String recipient : recipients) {
            String trimmedRecipient = recipient.trim();
            if (!trimmedRecipient.isEmpty()) {
                try {
                    emailServiceImpl.sendSimpleMessage(trimmedRecipient, "", message);
                } catch (Exception e) {
                    log.error("Failed to send SMS notification to {}: {}", trimmedRecipient, e.getMessage());
                }
            }
        }
    }

    /**
     * Get the display name for a vehicle - returns the friendly vehicle name if available, otherwise the VIN
     * @param vin Vehicle identification number
     * @return Vehicle name (e.g., "Xena Warrior Princess") or VIN as fallback
     */
    public String getVehicleDisplayName(String vin) {
        if (vin == null) {
            return "Unknown Vehicle";
        }
        String vehicleName = vinVehicleNameMap.get(vin);
        return (vehicleName != null && !vehicleName.isBlank()) ? vehicleName : vin;
    }

    /**
     * Build SMS notification message for preconditioning confirmation
     * For departures: "Preconditioning on [vehicle] for '[event]'. Depart at [time] to arrive 10 min early ([X] min travel time)"
     * For return home: "Return home preconditioning on [vehicle] for '[event]'. Estimated [X] min travel time home"
     * @param entity The preconditioning entity
     * @return Formatted SMS message
     */
    private String buildPreconditioningConfirmationSms(CalendarPreConditionLinkEntity entity) {
        String vehicleName = getVehicleDisplayName(entity.getVin());
        String eventName = entity.getEventSummary() != null ? entity.getEventSummary() : "Unknown Event";
        boolean isReturnHome = entity.getCalendarId().contains("_RETURN_HOME");
        
        try {
            // Get the event to calculate travel time
            Event event = googleCalendarService.getCalendar().getItems().stream()
                    .filter(e -> e.getId().equals(entity.getCalendarId().replace("_RETURN_HOME", "")))
                    .findFirst()
                    .orElse(null);
            
            if (event == null) {
                // Fallback if event not found
                return "Preconditioning on " + vehicleName + " scheduled for event '" + eventName + "'";
            }
            
            // Calculate travel time
            int travelTimeMinutes = 15; // Default
            try {
                double startLat, startLon;
                String destination;
                
                if (isReturnHome) {
                    // For return home: from current location to home
                    double[] currentLoc = getCurrentVehicleLocation(entity.getVin());
                    startLat = currentLoc[0];
                    startLon = currentLoc[1];
                    destination = homeLatitude + "," + homeLongitude;
                } else {
                    // For departure: from home/current location to event
                    startLat = entity.getStoredLatitude() != null ? entity.getStoredLatitude() : homeLatitude;
                    startLon = entity.getStoredLongitude() != null ? entity.getStoredLongitude() : homeLongitude;
                    destination = event.getLocation();
                }
                
                if (destination != null && !destination.isBlank()) {
                    travelTimeMinutes = Math.toIntExact(routesCalculationService
                            .calculateRouteToDestination(startLat, startLon, destination)
                            .getRoutesList().stream()
                            .max(Comparator.comparing(r -> r.getDuration().getSeconds()))
                            .map(r -> r.getDuration().getSeconds() / 60)
                            .orElse(15L));
                }
            } catch (Exception e) {
                log.debug("Could not calculate travel time for SMS, using default: {}", e.getMessage());
            }
            
            if (isReturnHome) {
                return "Return home preconditioning on " + vehicleName + " for '" + eventName + 
                        "'. Estimated " + travelTimeMinutes + " min travel time home";
            } else {
                // Calculate departure time
                ZonedDateTime eventTime = Instant.ofEpochMilli(event.getStart().getDateTime().getValue())
                        .atZone(ZoneId.of("America/New_York"));
                ZonedDateTime departureTime = eventTime.minusMinutes(travelTimeMinutes + preconditioningBufferMinutes);
                String departureTimeStr = String.format("%d:%02d %s", 
                        departureTime.getHour() > 12 ? departureTime.getHour() - 12 : (departureTime.getHour() == 0 ? 12 : departureTime.getHour()),
                        departureTime.getMinute(),
                        departureTime.getHour() >= 12 ? "PM" : "AM");
                
                return "Preconditioning on " + vehicleName + " for '" + eventName + 
                        "'. Depart at " + departureTimeStr + " to arrive " + preconditioningBufferMinutes + 
                        " min early (" + travelTimeMinutes + " min travel time)";
            }
        } catch (Exception e) {
            log.debug("Error building preconditioning SMS, using fallback: {}", e.getMessage());
            return "Preconditioning on " + vehicleName + " scheduled for event '" + eventName + "'";
        }
    }

    /**
     * Store a vehicle name for a VIN (extracted from telemetry or API response)
     * @param vin Vehicle identification number
     * @param vehicleName The friendly vehicle name
     */
    public void setVehicleName(String vin, String vehicleName) {
        if (vin != null && vehicleName != null && !vehicleName.isBlank()) {
            String previousName = vinVehicleNameMap.put(vin, vehicleName);
            if (previousName == null) {
                log.info("Learned vehicle name for VIN {}: '{}'", vin, vehicleName);
            } else if (!previousName.equals(vehicleName)) {
                log.info("Updated vehicle name for VIN {} from '{}' to '{}'", vin, previousName, vehicleName);
            }
        }
    }

    /**
     * Extract and store vehicle name from VehicleData API response
     * @param vin Vehicle identification number
     * @param vehicleData The VehicleData object from API response
     */
    private void extractVehicleNameFromApiResponse(String vin, VehicleData vehicleData) {
        if (vehicleData != null && vehicleData.getVehicle_state() != null) {
            String vehicleName = vehicleData.getVehicle_state().getVehicle_name();
            if (vehicleName != null && !vehicleName.isBlank()) {
                setVehicleName(vin, vehicleName);
            }
        }
    }

    /**
     * Calculate desired preconditioning time for an event (helper method)
     */
    private int calculateDesiredPreconditionTime(Event event, String vin) {
        int minutesToDestination = 15; // Default
        try {
            boolean useCurrentLocation = shouldUseCurrentLocationForScheduling(vin, event);
            double startLat = useCurrentLocation ? getCurrentVehicleLocation(vin)[0] : homeLatitude;
            double startLon = useCurrentLocation ? getCurrentVehicleLocation(vin)[1] : homeLongitude;

            minutesToDestination = Math.toIntExact(routesCalculationService
                    .calculateRouteToDestination(startLat, startLon, event.getLocation())
                    .getRoutesList().stream().max(Comparator.comparing(r -> r.getDuration().getSeconds()))
                    .get().getDuration().getSeconds() / 60);
        } catch (Exception e) {
            log.debug("Failed to calculate route for urgent check, using default: {}", e.getMessage());
        }

        ZonedDateTime eventTime = Instant.ofEpochMilli(event.getStart().getDateTime().getValue())
                .atZone(ZoneId.of("America/New_York"));
        int eventMinutesFromMidnight = eventTime.getHour() * 60 + eventTime.getMinute();

        return eventMinutesFromMidnight - minutesToDestination - preconditioningBufferMinutes;
    }

    /**
     * Record a telemetry timestamp and location for travel detection and check for travel state transitions.
     * This method is called when telemetry WITH location data arrives.
     */
    public void recordTelemetryTimestamp(String vin, long timestamp, Telemetry.Location location) {
        // Record that we received ANY telemetry
        vinLastTelemetryTime.put(vin, timestamp);
        
        vinTelemetryTimestamps.computeIfAbsent(vin, k -> new ArrayList<>()).add(timestamp);
        // Keep only recent timestamps (last 10 minutes)
        List<Long> timestamps = vinTelemetryTimestamps.get(vin);
        long tenMinutesAgo = timestamp - (10 * 60 * 1000L);
        timestamps.removeIf(t -> t < tenMinutesAgo);

        // Record location data and update last location time
        if (location != null) {
            vinLastLocationUpdateTime.put(vin, timestamp);
            vinTelemetryLocations.computeIfAbsent(vin, k -> new ArrayList<>())
                    .add(new LocationWithTimestamp(location.getLatitude(), location.getLongitude(), timestamp));
            // Keep only recent locations (last 10 minutes)
            List<LocationWithTimestamp> locations = vinTelemetryLocations.get(vin);
            locations.removeIf(loc -> loc.timestamp < tenMinutesAgo);
        }

        // Check for travel state transition
        checkAndUpdateTravelState(vin);
    }
    
    /**
     * Record that telemetry arrived WITHOUT location data.
     * This is critical for detecting when a vehicle has parked - location data stops but other telemetry continues.
     * If we're receiving telemetry but location data is stale (>5 min), the vehicle has likely parked.
     */
    public void recordTelemetryWithoutLocation(String vin, long timestamp) {
        // Record that we received telemetry (even without location)
        vinLastTelemetryTime.put(vin, timestamp);
        
        // Check for travel state transition - location staleness will be detected in isVehicleTraveling
        checkAndUpdateTravelState(vin);
    }
    
    /**
     * Check and update travel state, triggering actions on traveling→stationary transitions
     */
    private void checkAndUpdateTravelState(String vin) {
        boolean currentlyTraveling = isVehicleTraveling(vin);
        Boolean previouslyTraveling = vinPreviousTravelState.get(vin);

        // Detect transition from traveling to stationary
        if (previouslyTraveling != null && previouslyTraveling && !currentlyTraveling) {
            log.info("{} transitioned from traveling to stationary - checking for pending precondition updates", getVehicleDisplayName(vin));
            checkPendingPreconditionsForLocationUpdate(vin);
        }

        // Update previous state
        vinPreviousTravelState.put(vin, currentlyTraveling);
    }

    /**
     * Check pending preconditions for location updates when vehicle becomes stationary
     * @param vin Vehicle identification number
     */
    private void checkPendingPreconditionsForLocationUpdate(String vin) {
        // Run this asynchronously to avoid blocking telemetry processing
        scheduler.schedule(() -> {
            TransactionTemplate tt = new TransactionTemplate(transactionManager);
            tt.executeWithoutResult(status -> {
                try {
                    log.debug("Checking pending preconditions for VIN {} after travel-to-stationary transition", vin);

                    // Find all active preconditions for this VIN
                    // For departures: event hasn't started yet (unixStartTime > now)
                    // For return home: event HAS started (unixStartTime <= now) but not more than 12 hours ago
                    long now = System.currentTimeMillis();
                    long twelveHoursAgo = now - (12 * 60 * 60 * 1000L);
                    
                    List<CalendarPreConditionLinkEntity> pendingEntities = calendarPreConditionLinkRepository
                            .findAllByDeleted(false).stream()
                            .filter(entity -> vin.equals(entity.getVin()))
                            .filter(entity -> entity.getStatus() == PreconditioningStatus.ACTIVE ||
                                             entity.getStatus() == PreconditioningStatus.PENDING)
                            .filter(entity -> {
                                boolean isReturnHome = entity.getCalendarId().contains("_RETURN_HOME");
                                if (isReturnHome) {
                                    // Return home: process if event has STARTED but not more than 12 hours ago
                                    return entity.getUnixStartTime() <= now && entity.getUnixStartTime() > twelveHoursAgo;
                                } else {
                                    // Departures: process if event hasn't started yet
                                    return entity.getUnixStartTime() > now;
                                }
                            })
                            .toList();

                    if (pendingEntities.isEmpty()) {
                        log.debug("No pending preconditions found for VIN {}", vin);
                        return;
                    }

                    // Get current location
                    double[] currentLocation = getCurrentVehicleLocation(vin);
                    double currentLat = currentLocation[0];
                    double currentLon = currentLocation[1];

                    // Calculate distance from home
                    double distanceFromHome = calculateDistance(currentLat, currentLon, homeLatitude, homeLongitude);
                    boolean isNearHome = distanceFromHome <= 0.25; // Within 0.25 miles of home

                    // Check each pending precondition
                    for (CalendarPreConditionLinkEntity entity : pendingEntities) {
                        // Handle return home entities specially - cancel if vehicle is near home
                        if (entity.getCalendarId().contains("_RETURN_HOME")) {
                            if (isNearHome) {
                                // Vehicle is near home - cancel return home preconditioning since vehicle is already home
                                String eventName = entity.getEventSummary() != null ? entity.getEventSummary() : "Unknown Event";
                                Boolean wasAway = entity.getWasVehicleAway();
                                
                                if (wasAway != null && wasAway) {
                                    // Vehicle left and returned home - cancel since we're back
                                    log.info("Cancelling return home preconditioning for {} - {} returned home after being away",
                                            entity.getCalendarId(), getVehicleDisplayName(vin));
                                    cancelExistingTasks(entity.getCalendarId());
                                    entity.setStatus(PreconditioningStatus.EXPIRED);
                                    entity.setDeleted(true);
                                    calendarPreConditionLinkRepository.save(entity);
                                    
                                    sendSmsNotification("Return home preconditioning cancelled for " + getVehicleDisplayName(vin) + 
                                            " - vehicle returned home (event: '" + eventName + "')");
                                } else {
                                    // Vehicle never left home area - cancel since no trip was made
                                    log.info("Cancelling return home preconditioning for {} - {} is within 0.25 miles of home and never left",
                                            entity.getCalendarId(), getVehicleDisplayName(vin));
                                    cancelExistingTasks(entity.getCalendarId());
                                    entity.setStatus(PreconditioningStatus.EXPIRED);
                                    entity.setDeleted(true);
                                    calendarPreConditionLinkRepository.save(entity);
                                    
                                    sendSmsNotification("Return home preconditioning skipped for " + getVehicleDisplayName(vin) + 
                                            " - vehicle never left home (event: '" + eventName + "')");
                                }
                                continue;
                            } else {
                                // Vehicle is away from home - mark that it was away
                                if (entity.getWasVehicleAway() == null || !entity.getWasVehicleAway()) {
                                    entity.setWasVehicleAway(true);
                                    calendarPreConditionLinkRepository.save(entity);
                                    log.debug("Marked {} as having left home for return home entity {}", 
                                            getVehicleDisplayName(vin), entity.getCalendarId());
                                }
                            }
                        }

                        // Skip if no stored location (can't calculate distance change)
                        if (entity.getStoredLatitude() == null || entity.getStoredLongitude() == null) {
                            continue;
                        }

                        // Calculate distance from stored location
                        double storedLat = entity.getStoredLatitude();
                        double storedLon = entity.getStoredLongitude();
                        double distance = calculateDistance(currentLat, currentLon, storedLat, storedLon);

                        // If moved significantly (>0.5 miles), consider updating
                        if (distance > 0.5) {
                            log.info("{} moved {} miles from precondition location for event {} - considering update",
                                    getVehicleDisplayName(vin), String.format("%.2f", distance), entity.getCalendarId());

                            // Check if we should update this precondition
                            if (shouldUpdatePreconditionDueToLocationChange(entity, currentLat, currentLon)) {
                                log.info("Updating precondition for event {} due to location change after becoming stationary",
                                        entity.getCalendarId());
                                // Update even if preconditionId is 0 - we still want to notify and update stored params
                                updatePreconditionForNewLocation(entity, currentLat, currentLon);
                            }
                        }
                    }

                } catch (Exception e) {
                    log.warn("Error checking pending preconditions for location update: {}", e.getMessage());
                }
            });
        }, 5, TimeUnit.SECONDS); // Small delay to ensure vehicle is fully stationary
    }

    /**
     * Calculate total distance moved over the past 10 minutes using haversine formula
     * @param vin Vehicle identification number
     * @return distance in kilometers, or 0 if insufficient data
     */
    private double calculateTotalDistanceMoved(String vin) {
        List<LocationWithTimestamp> locations = vinTelemetryLocations.get(vin);
        if (locations == null || locations.size() < 2) {
            return 0.0;
        }

        double totalDistance = 0.0;
        LocationWithTimestamp prevLocation = locations.get(0);

        for (int i = 1; i < locations.size(); i++) {
            LocationWithTimestamp currentLocation = locations.get(i);
            totalDistance += haversineDistance(
                prevLocation.latitude, prevLocation.longitude,
                currentLocation.latitude, currentLocation.longitude
            );
            prevLocation = currentLocation;
        }

        return totalDistance;
    }

    /**
     * Calculate distance between two points using haversine formula
     * @return distance in kilometers
     */
    private double haversineDistance(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371.0; // Earth's radius in kilometers

        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                   Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                   Math.sin(dLon / 2) * Math.sin(dLon / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return R * c;
    }

    /**
     * Determine if a vehicle is currently traveling based on telemetry update frequency and distance moved.
     * 
     * IMPORTANT: This method also detects when a vehicle has PARKED by checking for stale location data.
     * When a vehicle parks, location telemetry typically stops while other telemetry (battery, temp) continues.
     * If we're receiving telemetry but location data is more than 5 minutes old, the vehicle has likely parked.
     * 
     * @param vin Vehicle identification number
     * @return true if vehicle appears to be traveling (frequent location updates AND significant movement)
     */
    public boolean isVehicleTraveling(String vin) {
        long currentTime = System.currentTimeMillis();
        
        // KEY FIX: Check if location data is stale while other telemetry is fresh
        // This detects when a vehicle has parked (location stops but battery/temp continues)
        Long lastLocationTime = vinLastLocationUpdateTime.get(vin);
        Long lastTelemetryTime = vinLastTelemetryTime.get(vin);
        
        if (lastLocationTime != null && lastTelemetryTime != null) {
            long locationAgeMs = currentTime - lastLocationTime;
            long telemetryAgeMs = currentTime - lastTelemetryTime;
            
            // If location data is more than 5 minutes old BUT we received other telemetry recently (within 2 min),
            // the vehicle has likely parked (location streaming stopped but other data continues)
            if (locationAgeMs > 5 * 60 * 1000L && telemetryAgeMs < 2 * 60 * 1000L) {
                log.debug("VIN {} detected as PARKED: location data is {}s old but other telemetry is {}s old",
                        vin, locationAgeMs / 1000, telemetryAgeMs / 1000);
                return false;
            }
        }
        
        List<Long> timestamps = vinTelemetryTimestamps.get(vin);
        if (timestamps == null || timestamps.isEmpty()) {
            // No recent data, assume not traveling
            return false;
        }

        // If no updates in the last 10 minutes, definitely not traveling
        if (timestamps.get(timestamps.size() - 1) < currentTime - 10 * 60 * 1000L) {
            return false;
        }

        if (timestamps.size() < 3) {
            // Not enough data, assume not traveling
            return false;
        }

        // Calculate average time between updates over the last 10 minutes
        long totalTimeSpan = timestamps.get(timestamps.size() - 1) - timestamps.get(0);
        if (totalTimeSpan == 0) {
            return false;
        }

        double averageIntervalSeconds = (double) totalTimeSpan / 1000 / (timestamps.size() - 1);

        // Check if updates are frequent enough (less than 5 minutes between updates)
        boolean frequentUpdates = averageIntervalSeconds < 300;

        // Calculate total distance moved over the past 10 minutes
        double totalDistanceKm = calculateTotalDistanceMoved(vin);

        // Consider traveling only if both frequent updates AND moved more than 0.2 km (200 meters)
        // This threshold can be adjusted based on observed behavior
        boolean significantMovement = totalDistanceKm > 0.2;

        boolean isTraveling = frequentUpdates && significantMovement;

        log.debug("VIN {} travel detection: {} timestamps, avg interval {}s, distance {}km, traveling: {} (freq: {}, move: {})",
                 vin, timestamps.size(), String.format("%.1f", averageIntervalSeconds),
                 String.format("%.3f", totalDistanceKm), isTraveling, frequentUpdates, significantMovement);

        return isTraveling;
    }

    /**
     * Check if a precondition should be updated due to location change
     * @param entity The precondition entity
     * @param newLat New latitude
     * @param newLon New longitude
     * @return true if update is needed
     */
    private boolean shouldUpdatePreconditionDueToLocationChange(CalendarPreConditionLinkEntity entity, double newLat, double newLon) {
        try {
            // Get the corresponding calendar event
            Event event = googleCalendarService.getCalendar().getItems().stream()
                    .filter(e -> e.getId().equals(entity.getCalendarId().replace("_RETURN_HOME", "")))
                    .findFirst()
                    .orElse(null);

            if (event == null) {
                log.debug("Calendar event not found for entity {}", entity.getCalendarId());
                return false;
            }

            // Calculate new desired preconditioning time from current location
            int newDesiredTime = calculateDesiredPreconditionTime(event, entity.getVin());

            // Check if timing changed significantly (>10 minutes)
            Integer oldDesiredTime = entity.getStoredPreconditionTime();
            if (oldDesiredTime == null || Math.abs(newDesiredTime - oldDesiredTime) > 10) {
                log.debug("Preconditioning timing changed from {} to {} minutes from midnight due to location change",
                         oldDesiredTime, newDesiredTime);
                return true;
            }

            // Check if location change is significant (>0.1 miles from stored)
            if (entity.getStoredLatitude() != null && entity.getStoredLongitude() != null) {
                double distance = calculateDistance(newLat, newLon, entity.getStoredLatitude(), entity.getStoredLongitude());
                if (distance > 0.1) {
                    return true;
                }
            }

            return false;

        } catch (Exception e) {
            log.warn("Error checking if precondition update needed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Update a precondition for new location after vehicle becomes stationary
     * @param entity The precondition entity
     * @param newLat New latitude
     * @param newLon New longitude
     */
    private void updatePreconditionForNewLocation(CalendarPreConditionLinkEntity entity, double newLat, double newLon) {
        try {
            // Get the corresponding calendar event
            Event event = googleCalendarService.getCalendar().getItems().stream()
                    .filter(e -> e.getId().equals(entity.getCalendarId().replace("_RETURN_HOME", "")))
                    .findFirst()
                    .orElse(null);

            if (event == null) {
                log.warn("Calendar event not found for entity {} during location update", entity.getCalendarId());
                return;
            }

            String vin = entity.getVin();
            String eventName = entity.getEventSummary() != null ? entity.getEventSummary() : "Unknown Event";
            boolean isReturnHome = entity.getCalendarId().contains("_RETURN_HOME");
            
            // Calculate new timing from current location
            ZonedDateTime eventTime = Instant.ofEpochMilli(event.getStart().getDateTime().getValue())
                    .atZone(ZoneId.of("America/New_York"));
            int newDesiredTime = calculateDesiredPreconditionTime(event, vin);
            int oldDesiredTime = entity.getStoredPreconditionTime() != null ? entity.getStoredPreconditionTime() : 0;
            int timeDifferenceMinutes = Math.abs(newDesiredTime - oldDesiredTime);

            // Update stored parameters
            smartScheduleManager.updateStoredParameters(entity, newDesiredTime,
                    eventTime.getDayOfWeek(), newLat, newLon);
            calendarPreConditionLinkRepository.save(entity);

            // Cancel existing scheduled task
            cancelExistingTasks(entity.getCalendarId());

            // Calculate new scheduling time (1 hour before new preconditioning time)
            ZonedDateTime newPreconditionStart = eventTime.toLocalDate()
                    .atTime(newDesiredTime / 60, newDesiredTime % 60)
                    .atZone(ZoneId.of("America/New_York"));

            // Handle negative preconditioning times (would be previous day)
            if (newDesiredTime < 0) {
                newPreconditionStart = newPreconditionStart.minusDays(1)
                        .withHour((24 * 60 + newDesiredTime) / 60)
                        .withMinute((24 * 60 + newDesiredTime) % 60);
            }

            long newTaskTime = newPreconditionStart.toEpochSecond() * 1000 - (60 * 60 * 1000L);
            long currentTime = System.currentTimeMillis();

            // Send SMS notification about the schedule update due to vehicle movement
            String scheduleType = isReturnHome ? "Return home preconditioning" : "Preconditioning";
            String timeChangeDesc = timeDifferenceMinutes > 0 ? 
                    String.format(" (timing adjusted by %d min)", timeDifferenceMinutes) : "";
            sendSmsNotification(scheduleType + " for " + getVehicleDisplayName(vin) + 
                    " updated due to vehicle movement" + timeChangeDesc + 
                    " - event: '" + eventName + "'");

            if (newTaskTime > currentTime + (5 * 60 * 1000L)) { // At least 5 minutes from now
                log.info("Rescheduling preconditioning task for event {} to new time based on updated location",
                        entity.getCalendarId());

                // Rebuild vinToEmailMap for the scheduled task
                List<String> emailsAndVins = Arrays.stream(vinCsv.split(",")).toList();
                Map<String, String> taskVinToEmailMap = emailsAndVins.stream()
                        .map(s -> s.split(":")).collect(java.util.stream.Collectors.toMap(s -> s[1], s -> s[0]));

                ScheduledFuture<?> newTask = scheduler.schedule(() -> {
                    executeScheduledPreconditioningTask(entity.getCalendarId(), taskVinToEmailMap);
                }, newTaskTime - currentTime, TimeUnit.MILLISECONDS);

                scheduledPreconditioningTasks.put(entity.getCalendarId(), newTask);
            } else {
                log.info("New preconditioning time too soon for event {} - will execute immediately", entity.getCalendarId());
                // Execute immediately if time is very close
                // Rebuild vinToEmailMap for the immediate task
                List<String> emailsAndVins = Arrays.stream(vinCsv.split(",")).toList();
                Map<String, String> immediateTaskVinToEmailMap = emailsAndVins.stream()
                        .map(s -> s.split(":")).collect(java.util.stream.Collectors.toMap(s -> s[1], s -> s[0]));

                scheduler.schedule(() -> {
                    executeScheduledPreconditioningTask(entity.getCalendarId(), immediateTaskVinToEmailMap);
                }, 30, TimeUnit.SECONDS); // Short delay
            }

        } catch (Exception e) {
            log.error("Error updating precondition for new location: {}", e.getMessage());
        }
    }

    // Lightweight scheduler for delayed tasks
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    
    // Track scheduled preconditioning tasks to allow cancellation
    private final Map<String, ScheduledFuture<?>> scheduledPreconditioningTasks = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> scheduledVerificationTasks = new ConcurrentHashMap<>();
    @Autowired
    private EmailServiceImpl emailServiceImpl;
    
    private double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        final double R = 3959; // Earth radius in miles
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat/2) * Math.sin(dLat/2) +
                   Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                   Math.sin(dLon/2) * Math.sin(dLon/2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
    }

    /**
    * Smart cleanup method to remove only irrelevant preconditioning schedules from a vehicle
    * Also detects and handles orphaned Tesla schedules (schedules without database entities)
    * @param vin Vehicle identification number
    * @param managedEntities List of calendar entities we're managing
    * @param currentEvents Current calendar events for context
    * @return List of preconditioning entries that could not be deleted
     */
    public List<VehicleData.PreconditionSchedule> smartCleanupPreconditioningSchedules(String vin,
    List<CalendarPreConditionLinkEntity> managedEntities,
                                                                                        List<Event> currentEvents) throws InterruptedException {
        log.info("=== SMART CLEANUP START ===");
        log.info("Starting smart preconditioning cleanup for {}", getVehicleDisplayName(vin));
        log.info("Managed entities count: {}", managedEntities.size());
        log.info("Current events count: {}", currentEvents.size());
        
        try {
            // Check API rate limits first
            if (!apiUsageTracker.canExecuteCommand(vin, VehicleApiUsageTracker.CommandType.GET_DATA)) {
                log.warn("API rate limit reached for VIN {}, deferring cleanup", vin);
                log.info("=== SMART CLEANUP END (RATE LIMITED) ===");
                return Collections.emptyList();
            }
        
            // Step 1: Wake the vehicle and verify it's awake (if needed)
            log.info("Step 1: Waking vehicle if needed");
            if (!wakeVehicleWithProperTracking(vin, 3)) {
                log.error("Failed to wake {} for preconditioning cleanup", getVehicleDisplayName(vin));
                log.info("=== SMART CLEANUP END (WAKE FAILED) ===");
                return Collections.emptyList();
            }
            
            // Step 2: Get current preconditioning schedules
            log.info("Step 3: Getting current preconditioning schedules");
            VehicleData vehicleData = fleetApiService.getVehicleDataNoCache(vin);
            apiUsageTracker.recordCommand(vin, VehicleApiUsageTracker.CommandType.GET_DATA);
            extractVehicleNameFromApiResponse(vin, vehicleData);
            
            List<VehicleData.PreconditionSchedule> allSchedules = safeGetPreconditionSchedules(vehicleData);
            log.info("Found {} preconditioning schedules on vehicle", allSchedules.size());
            if (allSchedules.isEmpty()) {
                log.info("No preconditioning schedules found on vehicle {}", vin);
                log.info("=== SMART CLEANUP END (NO SCHEDULES) ===");
                return Collections.emptyList();
            }
        
            // Step 3: Filter to only schedules that need cleanup (irrelevant ones)
            log.info("Step 4: Identifying schedules to delete");
            List<VehicleData.PreconditionSchedule> schedulesToDelete = new ArrayList<>(allSchedules.stream()
                    .filter(schedule -> !smartScheduleManager.isScheduleRelevant(schedule, managedEntities, currentEvents, fleetApiService, calendarPreConditionLinkRepository))
                    .toList());
            log.info("Found {} irrelevant schedules to delete", schedulesToDelete.size());
            
            if (schedulesToDelete.isEmpty()) {
                log.info("No irrelevant preconditioning schedules found on vehicle {}", vin);
                log.info("=== SMART CLEANUP END (NOTHING TO DELETE) ===");
                return Collections.emptyList();
            }
            
            log.info("Total schedules to delete: {} (out of {} total schedules)", 
                    schedulesToDelete.size(), allSchedules.size());
            
            // Log each schedule to be deleted
            for (VehicleData.PreconditionSchedule schedule : schedulesToDelete) {
                log.info("Schedule to delete: ID={}, Time={}, Day={}, Enabled={}", 
                        schedule.getId(), schedule.getPrecondition_time(), 
                        schedule.getDays_of_week(), schedule.getEnabled());
            }
        
            // Step 4: Delete each irrelevant schedule with retry logic and rate limiting
            log.info("Step 5: Deleting irrelevant schedules");
            Set<Long> failedDeletions = new HashSet<>();
            int maxRetries = 3;
            
            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                log.info("=== DELETION ATTEMPT {} ===", attempt);
            // Get remaining schedules to delete from our filtered list
            final int currentAttempt = attempt; // Make effectively final for lambda
            List<VehicleData.PreconditionSchedule> remainingSchedules = schedulesToDelete.stream()
                    .filter(schedule -> !failedDeletions.contains(schedule.getId()) || currentAttempt == maxRetries)
                    .toList();
            
            if (remainingSchedules.isEmpty()) {
                log.info("All irrelevant preconditioning schedules successfully deleted from vehicle {}", vin);
                return Collections.emptyList();
            }
            
            log.info("Deletion attempt {} for vehicle {}: {} irrelevant schedules remaining", 
                    attempt, vin, remainingSchedules.size());
            
            for (VehicleData.PreconditionSchedule schedule : remainingSchedules) {
                // Check API rate limits before each deletion
                if (!apiUsageTracker.canExecuteCommand(vin, VehicleApiUsageTracker.CommandType.DELETE_SCHEDULE)) {
                    log.warn("API rate limit reached during cleanup for VIN {}, stopping deletions", vin);
                    break;
                }
                
                try {
                    RemovePreconditionSchedule removePreconditionSchedule = new RemovePreconditionSchedule();
                    removePreconditionSchedule.setId(schedule.getId());
                    removePreconditionSchedule.setWait_for_completion(true);
                    
                    boolean deleted = fleetApiService.deletePreconditioningEntry(vin, removePreconditionSchedule);
                    apiUsageTracker.recordCommand(vin, VehicleApiUsageTracker.CommandType.DELETE_SCHEDULE);
                    
                    if (!deleted) {
                        failedDeletions.add(schedule.getId());
                        log.warn("Failed to delete irrelevant preconditioning schedule {} on vehicle {} (attempt {})", 
                                schedule.getId(), vin, attempt);
                    } else {
                        failedDeletions.remove(schedule.getId());
                        // Invalidate cache after successful deletion
                        invalidateVehicleDataCache(vin, "deleted preconditioning schedule " + schedule.getId());
                        log.debug("Successfully deleted irrelevant preconditioning schedule {} on vehicle {}", 
                                schedule.getId(), vin);
                    }
                    
                    // Small delay between deletions
                    Thread.sleep(2000);
                    
                } catch (Exception e) {
                    failedDeletions.add(schedule.getId());
                    log.error("Exception during preconditioning schedule deletion for schedule {} on vehicle {}: {}", 
                            schedule.getId(), vin, e.getMessage());
                }
            }
            
            // Delay before next attempt
            if (attempt < maxRetries) {
                Thread.sleep(1000L * 30); // 30 second delay between attempts
            }
        }

            // Step 5: Detect and handle orphaned Tesla schedules (schedules without database entities)
            log.info("Step 6: Detecting orphaned Tesla schedules");
            detectAndHandleOrphanedSchedules(vin, managedEntities, allSchedules, currentEvents);

            // Step 6: Final verification and return remaining problematic schedules
            log.info("Step 7: Final verification of cleanup results");
                log.info("=== SMART CLEANUP END (NO VERIFICATION) ===");
                return schedulesToDelete.stream()
                        .filter(schedule -> failedDeletions.contains(schedule.getId()))
                        .toList();
        } catch (Exception e) {
            log.error("=== SMART CLEANUP END (EXCEPTION) ===");
            log.error("Unexpected error during smart cleanup for VIN {}: {}", vin, e.getMessage(), e);
            // Return empty list on exception to avoid further processing errors
            return Collections.emptyList();
        }
    }

    /**
     * Detect and handle orphaned Tesla schedules (schedules on vehicle without database entities)
     * @param vin Vehicle identification number
     * @param managedEntities Current managed entities
     * @param allTeslaSchedules All schedules currently on the Tesla vehicle
     * @param currentEvents Current calendar events for context
     */
    private void detectAndHandleOrphanedSchedules(String vin, List<CalendarPreConditionLinkEntity> managedEntities,
                                                 List<VehicleData.PreconditionSchedule> allTeslaSchedules,
                                                 List<Event> currentEvents) {
        log.info("Checking for orphaned Tesla schedules on vehicle {}", vin);

        // Get all one-time enabled schedules from Tesla (our managed type)
        List<VehicleData.PreconditionSchedule> teslaOneTimeSchedules = allTeslaSchedules.stream()
                .filter(VehicleData.PreconditionSchedule::getEnabled)
                .filter(VehicleData.PreconditionSchedule::getOne_time)
                .toList();

        log.debug("Found {} one-time enabled schedules on Tesla vehicle", teslaOneTimeSchedules.size());

        // Check each Tesla schedule for corresponding database entity
        for (VehicleData.PreconditionSchedule teslaSchedule : teslaOneTimeSchedules) {
            boolean hasEntity = managedEntities.stream()
                    .anyMatch(entity -> entity.getPreconditionId() > 0 &&
                              entity.getPreconditionId() == teslaSchedule.getId());

            if (!hasEntity) {
                log.warn("🚨 ORPHANED SCHEDULE DETECTED: Tesla schedule ID {} exists on vehicle {} but has no database entity",
                        teslaSchedule.getId(), vin);

                // Try to determine if this might be recoverable
                boolean canRecover = canRecoverOrphanedSchedule(teslaSchedule, vin, currentEvents);

                if (canRecover) {
                    log.info("Attempting to recover orphaned schedule {} by creating database entity", teslaSchedule.getId());
                    recoverOrphanedSchedule(teslaSchedule, vin, currentEvents);
                } else {
                    log.info("Removing orphaned schedule {} from Tesla vehicle (cannot recover)", teslaSchedule.getId());
                    removeOrphanedSchedule(vin, teslaSchedule.getId());
                }
            }
        }
    }

    /**
     * Check if an orphaned schedule can be recovered by creating a database entity
     * @param schedule The orphaned Tesla schedule
     * @param vin Vehicle identification number
     * @param currentEvents Current calendar events
     * @return true if recovery is possible
     */
    private boolean canRecoverOrphanedSchedule(VehicleData.PreconditionSchedule schedule, String vin, List<Event> currentEvents) {
        try {
            // For recovery, we need to find a calendar event that could plausibly match this schedule
            // Check if there's a calendar event happening around the preconditioning time

            int scheduleTime = schedule.getPrecondition_time();
            Set<DayOfWeek> scheduleDays = fleetApiService.decodeDaysOfWeek(schedule.getDays_of_week());

            // Look for calendar events on the scheduled days within a reasonable time window
            ZonedDateTime now = ZonedDateTime.now(ZoneId.of("America/New_York"));
            for (Event event : currentEvents) {
                if (event.getStart() == null || event.getStart().getDateTime() == null) continue;

                ZonedDateTime eventTime = Instant.ofEpochMilli(event.getStart().getDateTime().getValue())
                        .atZone(ZoneId.of("America/New_York"));

                // Check if event is on a scheduled day
                if (!scheduleDays.contains(eventTime.getDayOfWeek())) continue;

                // Check if preconditioning time is reasonable for this event
                int eventMinutesFromMidnight = eventTime.getHour() * 60 + eventTime.getMinute();
                int calculatedPreconditionTime = eventMinutesFromMidnight - 15 - 10; // Default buffer

                // Allow some tolerance (±30 minutes)
                if (Math.abs(calculatedPreconditionTime - scheduleTime) <= 30) {
                    log.debug("Found potential matching calendar event {} for orphaned schedule at time {}",
                            event.getId(), scheduleTime);
                    return true;
                }
            }

            return false;

        } catch (Exception e) {
            log.warn("Error checking if orphaned schedule can be recovered: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Attempt to recover an orphaned schedule by creating a database entity
     * @param schedule The orphaned Tesla schedule
     * @param vin Vehicle identification number
     * @param currentEvents Current calendar events
     */
    private void recoverOrphanedSchedule(VehicleData.PreconditionSchedule schedule, String vin, List<Event> currentEvents) {
        try {
            // Find the best matching calendar event
            int scheduleTime = schedule.getPrecondition_time();
            Set<DayOfWeek> scheduleDays = fleetApiService.decodeDaysOfWeek(schedule.getDays_of_week());

            Event bestMatch = null;
            int bestTimeDiff = Integer.MAX_VALUE;

            for (Event event : currentEvents) {
                if (event.getStart() == null || event.getStart().getDateTime() == null) continue;

                ZonedDateTime eventTime = Instant.ofEpochMilli(event.getStart().getDateTime().getValue())
                        .atZone(ZoneId.of("America/New_York"));

                if (!scheduleDays.contains(eventTime.getDayOfWeek())) continue;

                int eventMinutesFromMidnight = eventTime.getHour() * 60 + eventTime.getMinute();
                int calculatedPreconditionTime = eventMinutesFromMidnight - 15 - 10;
                int timeDiff = Math.abs(calculatedPreconditionTime - scheduleTime);

                if (timeDiff < bestTimeDiff && timeDiff <= 30) {
                    bestMatch = event;
                    bestTimeDiff = timeDiff;
                }
            }

            if (bestMatch != null) {
                // Create database entity for this orphaned schedule
                CalendarPreConditionLinkEntity recoveredEntity = new CalendarPreConditionLinkEntity();
                recoveredEntity.setCalendarId(bestMatch.getId());
                recoveredEntity.setVin(vin);
                recoveredEntity.setPreconditionId(schedule.getId());
                recoveredEntity.setUnixStartTime(bestMatch.getStart().getDateTime().getValue());
                recoveredEntity.setStatus(PreconditioningStatus.ACTIVE);
                recoveredEntity.setStoredPreconditionTime(scheduleTime);
                recoveredEntity.setStoredDayOfWeek(scheduleDays.iterator().next().name());
                recoveredEntity.setEventSummary(bestMatch.getSummary());
                recoveredEntity.setLastVerifiedTimestamp(System.currentTimeMillis());

                calendarPreConditionLinkRepository.save(recoveredEntity);

                log.info("✅ Successfully recovered orphaned schedule {} by creating entity for calendar event {}",
                        schedule.getId(), bestMatch.getId());
            } else {
                log.warn("Could not find suitable calendar event to recover orphaned schedule {}", schedule.getId());
            }

        } catch (Exception e) {
            log.error("Error recovering orphaned schedule {}: {}", schedule.getId(), e.getMessage());
        }
    }

    /**
     * Remove an orphaned schedule from the Tesla vehicle
     * @param vin Vehicle identification number
     * @param scheduleId ID of the schedule to remove
     */
    private void removeOrphanedSchedule(String vin, long scheduleId) {
        try {
            if (!apiUsageTracker.canExecuteCommand(vin, VehicleApiUsageTracker.CommandType.DELETE_SCHEDULE)) {
                log.warn("Cannot remove orphaned schedule {} due to API rate limits", scheduleId);
                return;
            }

            RemovePreconditionSchedule removeRequest = new RemovePreconditionSchedule();
            removeRequest.setId(scheduleId);
            removeRequest.setWait_for_completion(true);

            boolean deleted = fleetApiService.deletePreconditioningEntry(vin, removeRequest);
            apiUsageTracker.recordCommand(vin, VehicleApiUsageTracker.CommandType.DELETE_SCHEDULE);

            if (deleted) {
                log.info("✅ Successfully removed orphaned schedule {} from Tesla vehicle {}", scheduleId, vin);
                // Invalidate cache after successful deletion
                invalidateVehicleDataCache(vin, "removed orphaned schedule " + scheduleId);
            } else {
                log.warn("Failed to remove orphaned schedule {} from Tesla vehicle {}", scheduleId, vin);
            }

        } catch (Exception e) {
            log.error("Error removing orphaned schedule {} from vehicle {}: {}", scheduleId, vin, e.getMessage());
        }
    }

    /**
     * Check if an event needs scheduling based on 1-hour rule
     * Only schedule if we're exactly 1 hour before the desired preconditioning time
     * @param event Calendar event to check
     * @param desiredPreconditioningTime Time in minutes from midnight when preconditioning should start
     * @param toleranceMinutes Tolerance window for "exactly 1 hour" (default 5 minutes)
     * @return true if event should be scheduled now
     */
    private boolean shouldScheduleEventNow(Event event, int desiredPreconditioningTime, int toleranceMinutes) {
        long currentTimeMillis = System.currentTimeMillis();
        
        // Calculate when preconditioning should start today
        ZonedDateTime now = Instant.ofEpochMilli(currentTimeMillis).atZone(ZoneId.of("America/New_York"));
        ZonedDateTime todayPreconditionStart = now.toLocalDate()
                .atTime(desiredPreconditioningTime / 60, desiredPreconditioningTime % 60)
                .atZone(ZoneId.of("America/New_York"));
        
        // If preconditioning time has passed today, check tomorrow
        if (todayPreconditionStart.toEpochSecond() * 1000 < currentTimeMillis) {
            todayPreconditionStart = todayPreconditionStart.plusDays(1);
        }
        
        long timeToPreconditioningMs = todayPreconditionStart.toEpochSecond() * 1000 - currentTimeMillis;
        long oneHourMs = 60 * 60 * 1000L;
        long toleranceMs = toleranceMinutes * 60 * 1000L;
        
        boolean shouldSchedule = Math.abs(timeToPreconditioningMs - oneHourMs) <= toleranceMs;
        
        if (shouldSchedule) {
            log.info("Event {} should be scheduled now - {} minutes until preconditioning time", 
                    event.getId(), timeToPreconditioningMs / (60 * 1000));
        } else {
            log.debug("Event {} not ready for scheduling - {} minutes until preconditioning time (need ~60 minutes)", 
                    event.getId(), timeToPreconditioningMs / (60 * 1000));
        }
        
        return shouldSchedule;
    }
    
    /**
     * Check if existing schedule is within compliance window (±5 minutes of desired time)
     * @param existingSchedule Current preconditioning schedule on vehicle
     * @param desiredTime Desired preconditioning time
     * @param dayOfWeek Expected day of week
     * @return true if schedule is compliant (within tolerance)
     */
    private boolean isScheduleCompliant(VehicleData.PreconditionSchedule existingSchedule, 
                                      int desiredTime, DayOfWeek dayOfWeek) {
        if (existingSchedule == null || !existingSchedule.getEnabled() || !existingSchedule.getOne_time()) {
            return false;
        }
        
        // Check day of week matches
        Set<DayOfWeek> scheduleDays = fleetApiService.decodeDaysOfWeek(existingSchedule.getDays_of_week());
        if (!scheduleDays.contains(dayOfWeek)) {
            log.debug("Schedule {} day mismatch: expected {}, got {}", 
                    existingSchedule.getId(), dayOfWeek, scheduleDays);
            return false;
        }
        
        // Check time is within 5-minute compliance window
        int timeDifference = Math.abs(existingSchedule.getPrecondition_time() - desiredTime);
        boolean compliant = timeDifference <= 5;
        
        if (compliant) {
            log.debug("Schedule {} is compliant: time diff {}min (≤5min)", 
                    existingSchedule.getId(), timeDifference);
        } else {
            log.debug("Schedule {} is non-compliant: time diff {}min (>5min)", 
                    existingSchedule.getId(), timeDifference);
        }
        
        return compliant;
    }
    
    /**
     * Wake vehicle and schedule up to 3 preconditioning entries when 1-hour threshold is met
     * This includes the triggering event plus up to 2 return home preconditions
     * @param vin Vehicle identification number
     * @param triggeringEntity The calendar entity that triggered this scheduling
     * @param allEvents All calendar events for context
     * @param allEntities All managed entities for this VIN
     * @param vinToEmailMap Email to VIN mapping
     * @return true if scheduling was successful
     */
    private boolean scheduleVehiclePreconditions(String vin, CalendarPreConditionLinkEntity triggeringEntity,
                                               List<Event> allEvents, List<CalendarPreConditionLinkEntity> allEntities,
                                               Map<String, String> vinToEmailMap) throws InterruptedException {
        
        log.info("=== SCHEDULING VEHICLE PRECONDITIONS START ===");
        log.info("VIN: {}, Triggering Event: {}", vin, triggeringEntity.getCalendarId());
        
        // Step 1: Wake the vehicle and run cleanup
        if (!wakeVehicleWithProperTracking(vin, 3)) {
            log.error("Failed to wake {} for preconditioning scheduling", getVehicleDisplayName(vin));
            log.info("=== SCHEDULING VEHICLE PRECONDITIONS END (WAKE FAILED) ===");
            return false;
        }
        
        // Step 2: Run cleanup now that vehicle is awake
        log.info("Running cleanup for VIN {} since vehicle is now awake", vin);
        try {
            smartCleanupPreconditioningSchedules(vin, allEntities, allEvents);
        } catch (Exception e) {
            log.warn("Cleanup failed for VIN {} but continuing with scheduling: {}", vin, e.getMessage());
        }
        
        // Step 3: Get current schedules after cleanup
        VehicleData vehicleData;
        if (apiUsageTracker.canExecuteCommand(vin, VehicleApiUsageTracker.CommandType.GET_DATA)) {
            vehicleData = fleetApiService.getVehicleDataNoCache(vin);
            apiUsageTracker.recordCommand(vin, VehicleApiUsageTracker.CommandType.GET_DATA);
            extractVehicleNameFromApiResponse(vin, vehicleData);
        } else {
            log.error("Cannot get vehicle data for {} due to API rate limits", getVehicleDisplayName(vin));
            log.info("=== SCHEDULING VEHICLE PRECONDITIONS END (RATE LIMITED) ===");
            return false;
        }
        
        List<VehicleData.PreconditionSchedule> currentSchedules = safeGetPreconditionSchedules(vehicleData);
        
        // Step 4: Find triggering event and calculate its parameters
        Event triggeringEvent = allEvents.stream()
                .filter(e -> e.getId().equals(triggeringEntity.getCalendarId()))
                .findFirst()
                .orElse(null);
                
        if (triggeringEvent == null) {
            log.error("Triggering event {} no longer exists", triggeringEntity.getCalendarId());
            log.info("=== SCHEDULING VEHICLE PRECONDITIONS END (NO EVENT) ===");
            return false;
        }
        
        // Step 5: Schedule up to 3 preconditions in priority order
        List<SchedulingTask> schedulingTasks = new ArrayList<>();
        
        // Priority 1: The triggering event
        schedulingTasks.add(createSchedulingTask(triggeringEvent, triggeringEntity, vin, allEvents));
        
        // Priority 2-3: Find next 2 return home opportunities for this VIN
        List<Event> vinEvents = findEventsForVin(vin, allEvents, allEntities);
        for (Event event : vinEvents) {
            if (schedulingTasks.size() >= 3) break;
            if (event.getId().equals(triggeringEvent.getId())) continue; // Skip triggering event
            
            // Check if this event needs return home scheduling
            if (shouldScheduleReturnHome(event, vinEvents)) {
                String returnHomeCalendarId = event.getId() + "_RETURN_HOME";
                CalendarPreConditionLinkEntity returnEntity = allEntities.stream()
                        .filter(e -> returnHomeCalendarId.equals(e.getCalendarId()))
                        .findFirst()
                        .orElse(null);
                        
                if (returnEntity != null) {
                    SchedulingTask returnTask = createReturnHomeSchedulingTask(event, returnEntity, vin);
                    if (returnTask != null) {
                        schedulingTasks.add(returnTask);
                    }
                    // If null, vehicle is traveling - will be scheduled when it becomes stationary
                }
            }
        }
        
        // Step 6: Execute scheduling tasks
        int successCount = 0;
        for (SchedulingTask task : schedulingTasks) {
            if (executeSchedulingTask(task, currentSchedules, vin)) {
                successCount++;
            }
        }
        
        log.info("Successfully scheduled {} out of {} preconditions for VIN {}", 
                successCount, schedulingTasks.size(), vin);
        log.info("=== SCHEDULING VEHICLE PRECONDITIONS END (SUCCESS: {}/{}) ===", 
                successCount, schedulingTasks.size());
        
        return successCount > 0;
    }
    
    /**
     * Inner class to represent a scheduling task
     */
    private static class SchedulingTask {
        final Event event;
        final CalendarPreConditionLinkEntity entity;
        final String vin;
        final int desiredTime;
        final DayOfWeek dayOfWeek;
        final double startLat;
        final double startLon;
        final boolean isReturnHome;
        
        public SchedulingTask(Event event, CalendarPreConditionLinkEntity entity, String vin, 
                            int desiredTime, DayOfWeek dayOfWeek, double startLat, double startLon, boolean isReturnHome) {
            this.event = event;
            this.entity = entity;
            this.vin = vin;
            this.desiredTime = desiredTime;
            this.dayOfWeek = dayOfWeek;
            this.startLat = startLat;
            this.startLon = startLon;
            this.isReturnHome = isReturnHome;
        }
    }
    
    /**
     * Create a scheduling task for a regular departure event
     */
    private SchedulingTask createSchedulingTask(Event event, CalendarPreConditionLinkEntity entity, 
                                              String vin, List<Event> allEvents) {
        ZonedDateTime eventTime = Instant.ofEpochMilli(event.getStart().getDateTime().getValue())
                .atZone(ZoneId.of("America/New_York"));
        int minutesFromMidnight = eventTime.getHour() * 60 + eventTime.getMinute();
        DayOfWeek dayOfWeek = eventTime.getDayOfWeek();
        
        // Use adaptive location for scheduling
        boolean useCurrentLocation = shouldUseCurrentLocationForScheduling(vin, event);
        double startLat = useCurrentLocation ? getCurrentVehicleLocation(vin)[0] : homeLatitude;
        double startLon = useCurrentLocation ? getCurrentVehicleLocation(vin)[1] : homeLongitude;
        
        int minutesToDestination;
        try {
            minutesToDestination = Math.toIntExact(routesCalculationService
                    .calculateRouteToDestination(startLat, startLon, event.getLocation())
                    .getRoutesList().stream().max(Comparator.comparing(r -> r.getDuration().getSeconds()))
                    .get().getDuration().getSeconds() / 60);
        } catch (Exception e) {
            log.warn("Failed to calculate route for event {}, using default 15 minutes", event.getId());
            minutesToDestination = 15;
        }
        
        int desiredTime = minutesFromMidnight - minutesToDestination - preconditioningBufferMinutes;
        
        return new SchedulingTask(event, entity, vin, desiredTime, dayOfWeek, 
                                startLat, startLon, false);
    }
    
    /**
     * Create a scheduling task for a return home event.
     * 
     * IMPORTANT: If the vehicle is currently traveling, this will return null to signal
     * that the task should be deferred until the vehicle becomes stationary.
     * This prevents scheduling preconditioning at a temporary/moving location.
     */
    private SchedulingTask createReturnHomeSchedulingTask(Event event, CalendarPreConditionLinkEntity entity, String vin) {
        ZonedDateTime returnTime = Instant.ofEpochMilli(event.getEnd().getDateTime().getValue())
                .atZone(ZoneId.of("America/New_York"));
        int minutesFromMidnight = returnTime.getHour() * 60 + returnTime.getMinute();
        DayOfWeek dayOfWeek = returnTime.getDayOfWeek();
        
        int minutesToHome;
        double vehicleLat = homeLatitude; // Default fallback
        double vehicleLon = homeLongitude; // Default fallback
        
        try {
            // Check if event has started (vehicle should be at destination)
            long currentTime = System.currentTimeMillis();
            long eventStartTime = event.getStart().getDateTime().getValue();
            
            if (currentTime >= eventStartTime) {
                // Event has started - check if vehicle is still traveling
                if (isVehicleTraveling(vin)) {
                    // Vehicle is still in transit - don't use the moving location!
                    // Return null to signal that this task should be deferred
                    log.info("Vehicle {} is still traveling - deferring return home preconditioning until stationary", 
                            getVehicleDisplayName(vin));
                    return null;
                }
                
                // Vehicle is stationary - use current location (should be at destination)
                double[] currentLocation = getCurrentVehicleLocation(vin);
                vehicleLat = currentLocation[0];
                vehicleLon = currentLocation[1];
                log.info("Using current vehicle location for return home calculation: {}, {} (event started, vehicle stationary)", 
                        vehicleLat, vehicleLon);
            } else {
                // Event hasn't started yet - estimate using home location as fallback
                log.debug("Event {} hasn't started yet, using home location for return route estimation", event.getId());
            }
            
            // Calculate route from vehicle's location back to home
//            minutesToHome = Math.toIntExact(routesCalculationService
//                    .calculateRouteToDestination(vehicleLat, vehicleLon, homeLatitude + "," + homeLongitude)
//                    .getRoutesList().stream().max(Comparator.comparing(r -> r.getDuration().getSeconds()))
//                    .get().getDuration().getSeconds() / 60);
        } catch (Exception e) {
            log.warn("Failed to calculate return route for event {}, using default 15 minutes", event.getId());
            minutesToHome = 15;
        }
        
        // For return home: car should be ready exactly at event END time (no buffer)
        int desiredTime = minutesFromMidnight;
        
        // For return home preconditioning, use actual vehicle location as start point
        return new SchedulingTask(event, entity, vin, desiredTime, dayOfWeek, 
                                vehicleLat, vehicleLon, true);
    }
    
    /**
     * Execute a scheduling task (check compliance and schedule if needed)
     */
    private boolean executeSchedulingTask(SchedulingTask task, 
                                        List<VehicleData.PreconditionSchedule> currentSchedules, 
                                        String vin) {
        try {
            // Check if we already have a compliant schedule
            VehicleData.PreconditionSchedule existingSchedule = currentSchedules.stream()
                    .filter(s -> s.getId().equals(task.entity.getPreconditionId()))
                    .findFirst()
                    .orElse(null);
            
            if (isScheduleCompliant(existingSchedule, task.desiredTime, task.dayOfWeek)) {
                log.info("Schedule for {} is already compliant, skipping", 
                        task.isReturnHome ? "return home" : "departure");
                task.entity.setStatus(PreconditioningStatus.ACTIVE);
                calendarPreConditionLinkRepository.save(task.entity);
                return true;
            }
            
            // Need to schedule new preconditioning
            if (!apiUsageTracker.canExecuteCommand(vin, VehicleApiUsageTracker.CommandType.ADD_SCHEDULE)) {
                log.warn("Cannot add schedule for {} due to API rate limits", 
                        task.isReturnHome ? "return home" : "departure");
                return false;
            }
            
            AddPreconditioningScheduleBody scheduleBody = new AddPreconditioningScheduleBody();
            scheduleBody.setLat(task.startLat);
            scheduleBody.setLon(task.startLon);
            scheduleBody.setEnabled(true);
            scheduleBody.setOne_time(true);
            scheduleBody.setWait_for_completion(true);
            scheduleBody.setPrecondition_time(task.desiredTime);
            scheduleBody.setDays_of_week(task.dayOfWeek.name().toUpperCase().charAt(0) +
                    task.dayOfWeek.name().substring(1).toLowerCase());
            
            if (fleetApiService.addPreconditioningEntry(vin, scheduleBody)) {
                apiUsageTracker.recordCommand(vin, VehicleApiUsageTracker.CommandType.ADD_SCHEDULE);
                // Invalidate cache after adding schedule
                invalidateVehicleDataCache(vin, "added preconditioning schedule");
                log.info("Successfully scheduled {} preconditioning for event {}", 
                        task.isReturnHome ? "return home" : "departure", task.event.getId());
                
                // Update entity parameters
                smartScheduleManager.updateStoredParameters(task.entity, task.desiredTime, 
                        task.dayOfWeek, task.startLat, task.startLon);
                
                // Schedule completion check
                scheduleCheckCompletion(task.event, task.entity, vin, task.desiredTime, task.dayOfWeek);
                return true;
            } else {
                log.warn("Failed to add {} preconditioning for event {}", 
                        task.isReturnHome ? "return home" : "departure", task.event.getId());
                task.entity.setStatus(PreconditioningStatus.FAILED);
                calendarPreConditionLinkRepository.save(task.entity);
                return false;
            }
            
        } catch (Exception e) {
            log.error("Error executing scheduling task for event {}: {}", task.event.getId(), e.getMessage(), e);
            task.entity.setStatus(PreconditioningStatus.FAILED);
            calendarPreConditionLinkRepository.save(task.entity);
            return false;
        }
    }
    
    /**
     * Find all events for a specific VIN
     */
    private List<Event> findEventsForVin(String vin, List<Event> allEvents, List<CalendarPreConditionLinkEntity> allEntities) {
        return allEvents.stream()
                .filter(event -> allEntities.stream()
                        .anyMatch(entity -> event.getId().equals(entity.getCalendarId()) && 
                                           vin.equals(entity.getVin())))
                .sorted(Comparator.comparing(e -> e.getStart().getDateTime().getValue()))
                .collect(Collectors.toList());
    }
    


    /**
     * Check if a VIN has any departures within the next hour (urgency detection)
     */
    public boolean hasUrgentDeparture(String vin, List<Event> allEvents, List<CalendarPreConditionLinkEntity> managedEntities) {
        long currentTime = System.currentTimeMillis();
        long oneHourFromNow = currentTime + (60 * 60 * 1000L);
        
        // Check regular calendar events
        for (Event event : allEvents) {
            if (event.getStart() == null || event.getStart().getDateTime() == null) {
                continue;
            }
            
            long eventStartTime = event.getStart().getDateTime().getValue();
            if (eventStartTime <= oneHourFromNow && eventStartTime > currentTime) {
                // Check if this event is for this VIN
                if (isEventForVin(event, vin, managedEntities)) {
                    log.debug("VIN {} has urgent departure: event {} starts in {} minutes", 
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
                    log.debug("VIN {} has urgent return home: event {} ends in {} minutes", 
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
     * Convenience method to wake vehicle with proper API usage tracking and wait times
     * @param vin Vehicle identification number
     * @param maxAttempts Maximum wake attempts
     * @return true if vehicle is awake, false otherwise
     */
    private boolean wakeVehicleWithProperTracking(String vin, int maxAttempts) throws InterruptedException {
        FleetApiService.WakeResult wakeResult = fleetApiService.delayedWakeRetryWithCacheInfo(maxAttempts, vin);
        if (!wakeResult.isAwake()) {
            return false;
        }
        
        // Only record API usage if not from cache
        if (!wakeResult.isFromCache()) {
            apiUsageTracker.recordCommand(vin, VehicleApiUsageTracker.CommandType.WAKE_VEHICLE);
            log.debug("Recorded wake API usage for VIN {}", vin);
        } else {
            log.debug("Skipped wake API usage recording for VIN {} (from cache)", vin);
        }
        
        // Wait for vehicle to be fully responsive (shorter wait if from cache)
        long waitTimeMs = wakeResult.isFromCache() ? 10000L : 60000L;  // 10s if cached, 60s if actually woken
        log.debug("Waiting {} seconds for vehicle {} to be responsive (cache: {})", 
                waitTimeMs / 1000, vin, wakeResult.isFromCache());
        Thread.sleep(waitTimeMs);
        
        // Invalidate cache after wake since vehicle state (including location) may have changed
        if (!wakeResult.isFromCache()) {
            invalidateVehicleDataCache(vin, "vehicle woken - state may have changed");
        }
        
        return true;
    }
    
    /**
     * Trigger smart cleanup for a specific VIN if needed (used after schedule modifications)
     */
    private void triggerSelectiveCleanup(String vin, List<CalendarPreConditionLinkEntity> managedEntities, List<Event> currentEvents) {
        try {
            List<CalendarPreConditionLinkEntity> vinEntities = managedEntities.stream()
                    .filter(entity -> vin.equals(entity.getVin()))
                    .toList();
            
            if (!vinEntities.isEmpty()) {
                log.debug("Triggering selective cleanup for VIN {} after schedule modification", vin);
                smartCleanupPreconditioningSchedules(vin, vinEntities, currentEvents);
            }
        } catch (Exception e) {
            log.warn("Failed to perform selective cleanup for VIN {} after schedule modification: {}", vin, e.getMessage());
        }
    }
    
    /**
     * Safely delete a preconditioning schedule with proper error handling and validation
     * @param vin Vehicle identification number
     * @param scheduleId ID of schedule to delete
     * @return true if successfully deleted or verified not to exist
     */
    private boolean safeDeletePreconditioningSchedule(String vin, Long scheduleId) throws InterruptedException {
        RemovePreconditionSchedule removePreconditionSchedule = new RemovePreconditionSchedule();
        removePreconditionSchedule.setId(scheduleId);
        removePreconditionSchedule.setWait_for_completion(true);
        
        boolean deleted = false;
        if (wakeVehicleWithProperTracking(vin, 2)) {
            deleted = fleetApiService.deletePreconditioningEntry(vin, removePreconditionSchedule);
        }
        
        // If deletion failed, verify by checking if schedule still exists
        if (!deleted) {
            log.debug("Deletion command failed, verifying schedule {} still exists on VIN {}", scheduleId, vin);
            // Don't wait again since we just woke the vehicle - use a shorter delay
            Thread.sleep(10000L); // 10 second verification delay
            VehicleData vehicleData = fleetApiService.getVehicleDataNoCache(vin);
            List<VehicleData.PreconditionSchedule> currentSchedules = safeGetPreconditionSchedules(vehicleData);
            
            // If schedule no longer exists, treat as successful deletion
            if (currentSchedules.stream().noneMatch(v -> v.getId().equals(scheduleId))) {
                log.info("Schedule {} no longer exists on VIN {}, treating deletion as successful", scheduleId, vin);
                return true;
            } else {
                log.warn("Schedule {} still exists on VIN {} after deletion attempt", scheduleId, vin);
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * Safely get preconditioning schedules from vehicle data with null safety checks and detailed logging
     */
    private List<VehicleData.PreconditionSchedule> safeGetPreconditionSchedules(VehicleData vehicleData) {
        if (vehicleData == null) {
            log.warn("VehicleData is null - Tesla API returned no data");
            return new ArrayList<>();
        }
        
        if (vehicleData.getPreconditioning_schedule_data() == null) {
            log.warn("Preconditioning_schedule_data is null - Tesla API didn't return this section. VehicleData available fields: {}", 
                    getAvailableVehicleDataFields(vehicleData));
            return new ArrayList<>();
        }
        
        if (vehicleData.getPreconditioning_schedule_data().getPrecondition_schedules() == null) {
            log.warn("Precondition_schedules list is null - empty schedule data section");
            return new ArrayList<>();
        }
        
        List<VehicleData.PreconditionSchedule> schedules = vehicleData.getPreconditioning_schedule_data().getPrecondition_schedules();
        log.debug("Retrieved {} preconditioning schedules from vehicle", schedules.size());
        
        if (schedules.isEmpty()) {
            log.info("Vehicle has zero preconditioning schedules (empty list)");
        } else {
            log.debug("Schedule IDs found: {}", schedules.stream().map(s -> s.getId()).toList());
        }
        
        return schedules;
    }
    
    /**
     * Helper method to log available vehicle data fields for debugging
     */
    private String getAvailableVehicleDataFields(VehicleData vehicleData) {
        StringBuilder fields = new StringBuilder();
        if (vehicleData.getDrive_state() != null) fields.append("drive_state,");
        if (vehicleData.getClimate_state() != null) fields.append("climate_state,");
        if (vehicleData.getCharge_state() != null) fields.append("charge_state,");
        if (vehicleData.getVehicle_state() != null) fields.append("vehicle_state,");
        if (vehicleData.getPreconditioning_schedule_data() != null) fields.append("preconditioning_schedule_data,");
        // Note: location_data field doesn't exist in VehicleData class, data is in drive_state
        return fields.toString();
    }

    /**
     * Result of current location retrieval
     */
    private static class CurrentLocationResult {
        private final boolean success;
        private final double[] location;
        private final String failureReason;
        
        public CurrentLocationResult(boolean success, double[] location, String failureReason) {
            this.success = success;
            this.location = location;
            this.failureReason = failureReason;
        }
        
        public boolean isSuccess() { return success; }
        public double[] getLocation() { return location; }
        public String getFailureReason() { return failureReason; }
    }
    
    /**
     * Get current vehicle location with success/failure tracking
     */
    private CurrentLocationResult getCurrentVehicleLocationWithResult(String vin) {
        try {
            if (vinLocationMap.get(vin) == null) {
                return new CurrentLocationResult(false, new double[]{homeLatitude, homeLongitude}, "API rate limits");
            }

            double[] actualLocation = new double[]{vinLocationMap.get(vin).getLatitude(), vinLocationMap.get(vin).getLongitude()};
            log.info("Successfully retrieved current location for VIN {}: {}, {}", vin, actualLocation[0], actualLocation[1]);
            return new CurrentLocationResult(true, actualLocation, null);
            
        } catch (Exception e) {
            log.warn("Exception while getting current vehicle location for VIN {}: {}", vin, e.getMessage());
            return new CurrentLocationResult(false, new double[]{homeLatitude, homeLongitude}, "Exception: " + e.getMessage());
        }
    }
    
    /**
     * Get current vehicle location (legacy method for backward compatibility)
     */
    private double[] getCurrentVehicleLocation(String vin) {
        return getCurrentVehicleLocationWithResult(vin).getLocation();
    }

    /**
     * Determine if we should use current vehicle location instead of home for preconditioning calculation
     * Two-phase approach: 
     * Phase 1: Early detection for out-of-metro vehicles (>100 miles from home)
     * Phase 2: Normal 4-hour window logic for local vehicles
     */
    private boolean shouldUseCurrentLocationForScheduling(String vin, Event event) {
        long eventStartTime = event.getStart().getDateTime().getValue();
        long currentTime = System.currentTimeMillis();
        long timeUntilEvent = eventStartTime - currentTime;
        
        log.debug("Current location check for VIN {}: Event in {} hours", vin, timeUntilEvent / (60 * 60 * 1000.0));
        
        // Get current vehicle location with explicit success/failure tracking
        CurrentLocationResult locationResult = getCurrentVehicleLocationWithResult(vin);
        
        if (!locationResult.isSuccess()) {
            log.info("Failed to get current vehicle location for VIN {} ({}), using home location", 
                    vin, locationResult.getFailureReason());
            return false;
        }
        
        double[] currentLocation = locationResult.getLocation();
        double distanceFromHome = calculateDistance(currentLocation[0], currentLocation[1], homeLatitude, homeLongitude);
        
        log.info("Vehicle {} location: {}, {} - Distance from home: {} miles", 
                vin, currentLocation[0], currentLocation[1], distanceFromHome);
        
        // Phase 1: Early detection for out-of-metro vehicles (beyond normal 4-hour window)
        if (timeUntilEvent > 4 * 60 * 60 * 1000) {
            // For vehicles beyond threshold distance from home, enable early current-location scheduling
            if (distanceFromHome > outOfMetroThresholdMiles) {
                log.info("Vehicle {} is out-of-metro ({} miles from home, threshold: {} miles), enabling early current-location scheduling", 
                        vin, distanceFromHome, outOfMetroThresholdMiles);
                return true;
            }
            
            log.debug("Event {} is more than 4 hours away and vehicle is local, using home location", event.getId());
            return false;
        }
        
        // Phase 2: Normal 4-hour window logic for local vehicles
        if (distanceFromHome < homeRadiusMiles) {
            log.info("{} is within home radius ({} miles), using home location for scheduling", getVehicleDisplayName(vin), homeRadiusMiles);
            return false;
        }
        
        // Vehicle is away from home within 4-hour window - use current location
        log.info("{} is away from home within 4-hour window, using current location for scheduling", getVehicleDisplayName(vin));
        return true;
    }

    /**
     * Check if there's another calendar event for the same assignee at the same location soon after this event
     */
    private boolean hasSubsequentEventAtSameLocation(Event currentEvent, List<Event> allEvents, String assigneeEmail) {
        String currentLocation = currentEvent.getLocation();
        if (currentLocation == null) return false;
        
        long currentEventEndTime = currentEvent.getEnd() != null && currentEvent.getEnd().getDateTime() != null 
            ? currentEvent.getEnd().getDateTime().getValue() 
            : currentEvent.getStart().getDateTime().getValue() + (60 * 60 * 1000); // Default 1 hour if no end time
        
        return allEvents.stream()
            .filter(e -> !e.getId().equals(currentEvent.getId()))
            .filter(e -> e.getLocation() != null)
            .filter(e -> isSameLocation(currentLocation, e.getLocation()))
            .filter(e -> isEventForAssignee(e, assigneeEmail))
            .anyMatch(e -> {
                long eventStartTime = e.getStart().getDateTime().getValue();
                long timeDifference = eventStartTime - currentEventEndTime;
                // Check if next event is within 4 hours
                return timeDifference >= 0 && timeDifference < 4 * 60 * 60 * 1000;
            });
    }

    /**
     * Check if two locations are considered the same (within reasonable proximity)
     */
    private boolean isSameLocation(String location1, String location2) {
        // Simple string matching for now - could be enhanced with geocoding
        return location1.toLowerCase().trim().equals(location2.toLowerCase().trim());
    }

    /**
     * Check if an event is for a specific assignee
     */
    private boolean isEventForAssignee(Event event, String assigneeEmail) {
        if (event.getAttendees() != null) {
            for (EventAttendee attendee : event.getAttendees()) {
                if (assigneeEmail.equals(attendee.getEmail())) {
                    return true;
                }
            }
        }
        return event.getCreator() != null && assigneeEmail.equals(event.getCreator().getEmail());
    }

    @Scheduled(cron = "0 */10 6-22 * * *")  // Every 10 minutes during active hours
    public void checkUrgentEvents() throws IOException, InterruptedException {
        log.info("Checking for urgent calendar events (within 1 hour of start time)");
        List<Event> events = googleCalendarService.getCalendar().getItems().stream()
                .filter(e -> e.getStart().getDateTime() != null)
                .toList();

        List<String> emailsAndVins = Arrays.stream(vinCsv.split(",")).toList();
        Map<String, String> emailToVinMap = emailsAndVins.stream()
        .collect(Collectors.toMap(s -> s.split(":")[0], s -> s.split(":")[1]));
        Map<String, String> vinToEmailMap = emailToVinMap.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey, (a,b) -> a));

        // Check for events that need urgent scheduling (added within last 10 minutes, within 1 hour of start)
        long now = System.currentTimeMillis();
        for (Event event : events) {
            if (event.getLocation() == null) continue;

            long eventStartTime = event.getStart().getDateTime().getValue();
            long timeUntilStart = eventStartTime - now;

            // Only process events starting within 1 hour
            if (timeUntilStart < 0 || timeUntilStart > (60 * 60 * 1000L)) {
                continue;
            }

            // Check if this event needs urgent scheduling
            String assigneeEmail = determineEventAssignee(event);
            if (assigneeEmail == null) continue;

            String vin = emailToVinMap.get(assigneeEmail);

            // Check if entity exists
            Optional<CalendarPreConditionLinkEntity> existingEntity =
                    calendarPreConditionLinkRepository.findFirstByCalendarId(event.getId());

            if (existingEntity.isPresent()) {
                // Entity exists - only process if it's still PENDING
                if (existingEntity.get().getStatus() == PreconditioningStatus.PENDING) {
                    // Check if we should schedule now (within urgent window)
                    int desiredPreconditionTime = calculateDesiredPreconditionTime(event, vin);
                    if (shouldScheduleEventNow(event, desiredPreconditionTime, 15)) { // 15 min tolerance for urgent
                        log.info("Urgent scheduling triggered for existing event {} - within 1 hour window", event.getId());
                        processCalendarEventWithTaskScheduling(event, vinToEmailMap, events);
                    }
                }
            } else {
                // Entity doesn't exist - this is a new urgent event that needs to be tracked and scheduled
                log.info("Creating tracking entry for new urgent event {} (within 1 hour of start)", event.getId());
                processCalendarEventWithTaskScheduling(event, emailToVinMap, events);
            }
        }
    }

    @Scheduled(cron = "0 20,50 6-22 * * *")
    public void statusPrinter() {
        log.info("Current Tracked Calendar entries:");
        Set<String> vinsWithEntries = new HashSet<>();
        for (CalendarPreConditionLinkEntity entity : calendarPreConditionLinkRepository.findAllByDeleted(false).stream().sorted(Comparator.comparing(CalendarPreConditionLinkEntity::getUnixStartTime).thenComparing(CalendarPreConditionLinkEntity::getVin)).toList()) {
            if (!vinsWithEntries.contains(entity.getVin())) {
                log.info("Vin: {}", entity.getVin());
                vinsWithEntries.add(entity.getVin());
            }
            LocalDateTime localDateTime = Instant.ofEpochSecond(entity.getUnixStartTime() / 1000).atZone(ZoneId.of("America/New_York")).toLocalDateTime();
            log.info("\tStart time: {}, CalendarId: {}, PreconditioningId: {}, Status: {}", localDateTime.getDayOfWeek() + " at " + localDateTime.getHour() + ":" + (localDateTime.getMinute() < 10 ? "0" + localDateTime.getMinute() : localDateTime.getMinute()), entity.getCalendarId(), entity.getPreconditionId(), entity.getStatus());
        }
    }

    @Transactional
    @Scheduled(cron = "0 0,30 6-22 * * *")
    public List<CalendarPreConditionLinkEntity> runPreConditionScheduleCheck() throws IOException, InterruptedException {
        log.info("Running precondition schedule check");
        
        // Clean up expired wake cache entries
        fleetApiService.cleanupWakeCache();
        List<Event> events = googleCalendarService.getCalendar().getItems().stream().filter(e -> e.getStart().getDateTime() != null).toList();
        List<String> emailsAndVins = Arrays.stream(vinCsv.split(",")).toList();
        Map<String, String> emailToVinMap = emailsAndVins.stream()
        .collect(Collectors.toMap(s -> s.split(":")[0], s -> s.split(":")[1]));
        Map<String, String> vinToEmailMap = emailToVinMap.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey, (a,b) -> a));
        // Phase 1: Track all calendar events (updated approach)
        addMissingPreconditioningEntries(events, emailToVinMap, events);
        
        // Phase 2: Remove entities for deleted calendar events
        cleanupDeletedCalendarEvents(events);

        return calendarPreConditionLinkRepository.findAllByDeleted(false);
    }

    public List<CalendarPreConditionLinkEntity> getCurrentEntries() {
        return calendarPreConditionLinkRepository.findAllByDeleted(false);
    }

    @Transactional
    public void addMissingPreconditioningEntries(List<Event> events, Map<String, String> emailToVinMap, List<Event> allEvents) throws InterruptedException {
        log.info("Running task-based preconditioning management - tracking events and scheduling future tasks");
        
        // Phase 1: Track all calendar events and schedule precise-timing tasks
        for (Event event : events) {
            if (event.getLocation() == null) continue;
            processCalendarEventWithTaskScheduling(event, emailToVinMap, allEvents);
        }
        
        // Phase 2: Add return home preconditioning tracking and task scheduling
        scheduleReturnHomePreconditioning(events, allEvents, emailToVinMap);
    }
    
    /**
     * Process calendar event with task-based scheduling approach
     * During 30-minute scan: track event and schedule future 1-hour-before task
     */
    private synchronized void processCalendarEventWithTaskScheduling(Event event, Map<String, String> emailToVinMap, List<Event> allEvents) {
    // Build vinToEmailMap for compatibility
    Map<String, String> vinToEmailMap = emailToVinMap.entrySet().stream()
    .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey, (a,b) -> a));

    // Check if event has already started - no departure preconditioning needed
    if (System.currentTimeMillis() >= event.getStart().getDateTime().getValue()) {
        log.debug("Event {} already started, skipping departure preconditioning task", event.getId());
        return;
        }

        // Track the calendar event (create or update entity)
        CalendarPreConditionLinkEntity entity = trackCalendarEvent(event, emailToVinMap, allEvents);
        if (entity == null) return; // Event couldn't be tracked (no valid assignee)
        
        // Check if we already have a task scheduled for this event
        if (scheduledPreconditioningTasks.containsKey(entity.getCalendarId())) {
            ScheduledFuture<?> existingTask = scheduledPreconditioningTasks.get(entity.getCalendarId());
            if (existingTask != null && !existingTask.isCancelled() && !existingTask.isDone()) {
                log.debug("Preconditioning task already scheduled for {}, skipping duplicate", entity.getCalendarId());
                return;
            } else {
                // Remove stale task reference
                scheduledPreconditioningTasks.remove(entity.getCalendarId());
            }
        }
        
        // Calculate when we should wake vehicle and send schedule (1 hour before preconditioning time)
        long scheduleTaskTime = calculateScheduleTaskTime(event, entity);
        if (scheduleTaskTime == -1 || scheduleTaskTime <= System.currentTimeMillis()) {
            log.debug("Cannot schedule task for event {} - event may have passed or be too soon", event.getId());
            return;
        }
        
        // Cancel any existing scheduled task for this event (redundant but kept for safety)
        cancelExistingTasks(entity.getCalendarId());
        
        // Schedule the 1-hour-before task
        long delayMs = scheduleTaskTime - System.currentTimeMillis();
        log.info("Scheduling preconditioning task for event {} in {} minutes", 
                event.getId(), delayMs / (60 * 1000));
        
        ScheduledFuture<?> scheduledTask = scheduler.schedule(() -> {
            executeScheduledPreconditioningTask(entity.getCalendarId(), vinToEmailMap);
        }, delayMs, TimeUnit.MILLISECONDS);
        
        // Track the scheduled task for potential cancellation
        scheduledPreconditioningTasks.put(entity.getCalendarId(), scheduledTask);
    }
    
    /**
     * Calculate when to schedule the vehicle wake task (1 hour before preconditioning)
     */
    private long calculateScheduleTaskTime(Event event, CalendarPreConditionLinkEntity entity) {
        ZonedDateTime eventTime = Instant.ofEpochMilli(event.getStart().getDateTime().getValue())
                .atZone(ZoneId.of("America/New_York"));
        int minutesFromMidnight = eventTime.getHour() * 60 + eventTime.getMinute();
        
        // Get route duration
        int minutesToDestination = 15; // Default
        try {
            boolean useCurrentLocation = shouldUseCurrentLocationForScheduling(entity.getVin(), event);
            double startLat = useCurrentLocation ? getCurrentVehicleLocation(entity.getVin())[0] : homeLatitude;
            double startLon = useCurrentLocation ? getCurrentVehicleLocation(entity.getVin())[1] : homeLongitude;
            
            minutesToDestination = Math.toIntExact(routesCalculationService
                    .calculateRouteToDestination(startLat, startLon, event.getLocation())
                    .getRoutesList().stream().max(Comparator.comparing(r -> r.getDuration().getSeconds()))
                    .get().getDuration().getSeconds() / 60);
        } catch (Exception e) {
            log.debug("Failed to calculate route for task scheduling, using default: {}", e.getMessage());
        }
        
        int desiredPreconditionTime = minutesFromMidnight - minutesToDestination - preconditioningBufferMinutes;
        
        // Calculate when preconditioning should start on the actual event date
        ZonedDateTime preconditionStart = eventTime.toLocalDate()
                .atTime(desiredPreconditionTime / 60, desiredPreconditionTime % 60)
                .atZone(ZoneId.of("America/New_York"));
        
        // Handle negative preconditioning times (would be previous day)
        if (desiredPreconditionTime < 0) {
            preconditionStart = preconditionStart.minusDays(1)
                    .withHour((24 * 60 + desiredPreconditionTime) / 60)
                    .withMinute((24 * 60 + desiredPreconditionTime) % 60);
        }
        
        // Schedule task for 1 hour before preconditioning time
        long taskTime = preconditionStart.toEpochSecond() * 1000 - (60 * 60 * 1000L);
        
        // If the calculated task time is in the past, it means the event is too soon or has passed
        if (taskTime <= System.currentTimeMillis()) {
            if ((preconditionStart.toEpochSecond() * 1000) - (10 * 60 * 1000) >= System.currentTimeMillis()) {
                log.debug("Less than one hour before preconditioning time, but we can still schedule the task - Planning riskier scheduling with shorter window");
                return  System.currentTimeMillis() + (((preconditionStart.toEpochSecond() * 1000) - (10 * 60 * 1000) - System.currentTimeMillis()) / 2);
            }
            log.debug("Task time is in the past for event {} - event may have passed or be too soon", event.getId());
            return -1; // Signal that no task should be scheduled
        }
        
        return taskTime;
    }
    
    /**
     * Cancel existing scheduled tasks for an event
     */
    private void cancelExistingTasks(String calendarId) {
        ScheduledFuture<?> existingTask = scheduledPreconditioningTasks.remove(calendarId);
        if (existingTask != null && !existingTask.isDone()) {
            existingTask.cancel(false);
            log.debug("Cancelled existing preconditioning task for event {}", calendarId);
        }
        
        ScheduledFuture<?> existingVerification = scheduledVerificationTasks.remove(calendarId);
        if (existingVerification != null && !existingVerification.isDone()) {
            existingVerification.cancel(false);
            log.debug("Cancelled existing verification task for event {}", calendarId);
        }
    }
    
    /**
     * Execute the scheduled preconditioning task with dynamic rescheduling capability
     * Checks if vehicle has moved significantly closer and reschedules if appropriate
     */
    private void executeScheduledPreconditioningTask(String calendarId, Map<String, String> vinToEmailMap) {
        TransactionTemplate tt = new TransactionTemplate(transactionManager);
        tt.executeWithoutResult(status -> {
            try {
                log.info("=== EXECUTING SCHEDULED PRECONDITIONING TASK ===");
                log.info("Calendar Event: {}", calendarId);
                
                // Re-fetch entity to get current state
                Optional<CalendarPreConditionLinkEntity> entityOpt = calendarPreConditionLinkRepository.findFirstByCalendarId(calendarId);
                if (entityOpt.isEmpty()) {
                    log.warn("Entity for calendar event {} no longer exists, skipping", calendarId);
                    return;
                }
                
                CalendarPreConditionLinkEntity entity = entityOpt.get();
                
                // Skip if already processed or expired
                if (entity.getStatus() == PreconditioningStatus.ACTIVE || 
                    entity.getStatus() == PreconditioningStatus.EXPIRED ||
                    entity.isDeleted()) {
                    log.info("Event {} already processed or expired, skipping", calendarId);
                    return;
                }
                
                // Get corresponding calendar event
                Event event = googleCalendarService.getCalendar().getItems().stream()
                        .filter(e -> e.getId().equals(entity.getCalendarId().replace("_RETURN_HOME", "")))
                        .findFirst()
                        .orElse(null);
                        
                if (event == null) {
                    log.error("Calendar event {} no longer exists during task execution", entity.getCalendarId());
                    entity.setStatus(PreconditioningStatus.FAILED);
                    calendarPreConditionLinkRepository.save(entity);
                    return;
                }
                
                // DYNAMIC RESCHEDULING CHECK: Has vehicle moved significantly closer?
                if (reschedulingEnabled && shouldRescheduleTaskDueToMovement(entity, event, vinToEmailMap)) {
                    log.info("Rescheduling task due to significant vehicle movement closer to destination");
                    return; // Task has been rescheduled, exit current execution
                }
                
                // Proceed with normal execution - vehicle wake and scheduling
                boolean success = executeVehicleWakeAndScheduling(entity, vinToEmailMap);
                
                if (!success) {
                    // Schedule verification task for 10 minutes later
                    scheduleVerificationTask(calendarId, vinToEmailMap, 10);
                } else {
                    scheduleVerificationTask(calendarId, vinToEmailMap, 1);
                }
                
                log.info("=== SCHEDULED PRECONDITIONING TASK COMPLETE ===");
                
            } catch (Exception e) {
                log.error("Error executing scheduled preconditioning task for {}: {}", calendarId, e.getMessage(), e);
            }
        });
    }
    
    /**
     * Check if the scheduled task should be rescheduled due to significant vehicle movement
     * Only reschedules if vehicle location has changed AND vehicle is not currently traveling
     * @param entity The preconditioning entity
     * @param event The calendar event
     * @param vinToEmailMap VIN to email mapping
     * @return true if task was rescheduled, false if should proceed with current execution
     */
    private boolean shouldRescheduleTaskDueToMovement(CalendarPreConditionLinkEntity entity, Event event, Map<String, String> vinToEmailMap) {
        try {
            String vin = entity.getVin();
            long eventStartTime = event.getStart().getDateTime().getValue();
            long currentTime = System.currentTimeMillis();
            long timeUntilEvent = eventStartTime - currentTime;
            
            // Only consider rescheduling if we have sufficient time left (configurable minimum)
            long minimumTimeMs = minimumTimeRemainingHours * 60 * 60 * 1000L;
            if (timeUntilEvent < minimumTimeMs) {
                log.debug("Event {} less than {} hours away, proceeding with current execution", 
                         event.getId(), minimumTimeRemainingHours);
                return false;
            }
            
            // Get current vehicle location (with API rate limit check)
            if (!apiUsageTracker.canExecuteCommand(vin, VehicleApiUsageTracker.CommandType.GET_DATA)) {
                log.debug("Cannot check vehicle location for rescheduling due to API rate limits");
                return false;
            }
            
            CurrentLocationResult locationResult = getCurrentVehicleLocationWithResult(vin);
            if (!locationResult.isSuccess()) {
                log.debug("Cannot get current vehicle location for rescheduling check: {}", locationResult.getFailureReason());
                return false;
            }
            
            double[] currentLocation = locationResult.getLocation();
            double currentLat = currentLocation[0];
            double currentLon = currentLocation[1];
            
            // Compare current location to stored location when task was originally scheduled
            double originalLat = entity.getStoredLatitude() != null ? entity.getStoredLatitude() : homeLatitude;
            double originalLon = entity.getStoredLongitude() != null ? entity.getStoredLongitude() : homeLongitude;
            
            double movementDistance = calculateDistance(currentLat, currentLon, originalLat, originalLon);
            
            // Only reschedule if vehicle moved significantly (configurable threshold)
            if (movementDistance < movementThresholdMiles) {
            log.debug("Vehicle movement {} miles is not significant enough for rescheduling (threshold: {} miles)",
            movementDistance, movementThresholdMiles);
            return false;
            }

            // Only reschedule if vehicle is not currently traveling (stationary)
            // This prevents rescheduling while vehicle is in transit
            if (isVehicleTraveling(vin)) {
                log.debug("Vehicle {} is currently traveling (frequent telemetry updates), deferring rescheduling until stationary",
                         vin);
                return false;
            }
            
            // Calculate travel times from original vs current location to destination
            if (event.getLocation() == null || event.getLocation().trim().isEmpty()) {
                return false;
            }
            
            try {
                int originalTravelTime = Math.toIntExact(routesCalculationService
                        .calculateRouteToDestination(originalLat, originalLon, event.getLocation())
                        .getRoutesList().stream().max(Comparator.comparing(r -> r.getDuration().getSeconds()))
                        .get().getDuration().getSeconds() / 60);
                        
                int currentTravelTime = Math.toIntExact(routesCalculationService
                        .calculateRouteToDestination(currentLat, currentLon, event.getLocation())
                        .getRoutesList().stream().max(Comparator.comparing(r -> r.getDuration().getSeconds()))
                        .get().getDuration().getSeconds() / 60);
                
                int travelTimeSavings = originalTravelTime - currentTravelTime;
                
                // Reschedule if we save more than the configured threshold of travel time
                if (travelTimeSavings > timeSavingsThresholdMinutes) {
                    log.info("Vehicle moved {} miles closer, saving {} minutes travel time (threshold: {} min) - rescheduling task", 
                            movementDistance, travelTimeSavings, timeSavingsThresholdMinutes);
                    
                    // Calculate new optimal scheduling time
                    ZonedDateTime eventTime = Instant.ofEpochMilli(eventStartTime).atZone(ZoneId.of("America/New_York"));
                    int minutesFromMidnight = eventTime.getHour() * 60 + eventTime.getMinute();
                    int newDesiredTime = minutesFromMidnight - currentTravelTime - preconditioningBufferMinutes;
                    
                    // Update stored parameters with new location and timing
                    smartScheduleManager.updateStoredParameters(entity, newDesiredTime, 
                            eventTime.getDayOfWeek(), currentLat, currentLon);
                    calendarPreConditionLinkRepository.save(entity);
                    
                    // Cancel current task and reschedule for new optimal time
                    cancelExistingTasks(entity.getCalendarId());
                    
                    // Calculate new scheduling time (1 hour before new preconditioning time)
                    ZonedDateTime newPreconditionStart = eventTime.toLocalDate()
                            .atTime(newDesiredTime / 60, newDesiredTime % 60)
                            .atZone(ZoneId.of("America/New_York"));
                    long newTaskTime = newPreconditionStart.toEpochSecond() * 1000 - (60 * 60 * 1000L);
                    
                    if (newTaskTime > currentTime) {
                        long newDelayMs = newTaskTime - currentTime;
                        log.info("Rescheduling preconditioning task for event {} in {} minutes", 
                                event.getId(), newDelayMs / (60 * 1000));
                        
                        ScheduledFuture<?> newTask = scheduler.schedule(() -> {
                            executeScheduledPreconditioningTask(entity.getCalendarId(), vinToEmailMap);
                        }, newDelayMs, TimeUnit.MILLISECONDS);
                        
                        scheduledPreconditioningTasks.put(entity.getCalendarId(), newTask);
                        return true; // Task rescheduled
                    } else {
                        log.info("New optimal time has passed, proceeding with immediate execution");
                        return false; // Proceed with current execution
                    }
                } else {
                    log.debug("Travel time savings {} minutes not significant enough for rescheduling (threshold: {} min)", 
                             travelTimeSavings, timeSavingsThresholdMinutes);
                    return false;
                }
                
            } catch (Exception e) {
                log.debug("Failed to calculate travel times for rescheduling check: {}", e.getMessage());
                return false;
            }
            
        } catch (Exception e) {
            log.warn("Error during rescheduling check for event {}: {}", event.getId(), e.getMessage());
            return false;
        }
    }
    
    /**
     * Execute vehicle wake and preconditioning scheduling for a specific event
     * Recalculates routes based on current vehicle location to handle vehicle movement
     */
    private boolean executeVehicleWakeAndScheduling(CalendarPreConditionLinkEntity entity, Map<String, String> vinToEmailMap) {
        try {
            String vin = entity.getVin();
            
            // Wake vehicle first to get accurate location data
            if (!wakeVehicleWithProperTracking(vin, 2)) {
                log.error("Failed to wake vehicle {} for event {}", vin, entity.getCalendarId());
                entity.setStatus(PreconditioningStatus.FAILED);
                calendarPreConditionLinkRepository.save(entity);
                return false;
            }
            
            // Recalculate timing based on current vehicle location (vehicle may have moved!)
            Event event = googleCalendarService.getCalendar().getItems().stream()
                    .filter(e -> e.getId().equals(entity.getCalendarId().replace("_RETURN_HOME", "")))
                    .findFirst()
                    .orElse(null);
                    
            if (event == null) {
                log.error("Calendar event {} no longer exists during execution", entity.getCalendarId());
                entity.setStatus(PreconditioningStatus.FAILED);
                calendarPreConditionLinkRepository.save(entity);
                return false;
            }
            
            // Check if vehicle has moved significantly and adjust timing
            boolean timingChanged = validateAndAdjustTimingForCurrentLocation(entity, event, vin);
            if (timingChanged) {
                log.info("Vehicle location changed for event {}, recalculated preconditioning timing", entity.getCalendarId());
            }
            
            // Run cleanup now that vehicle is awake
            List<CalendarPreConditionLinkEntity> allEntities = calendarPreConditionLinkRepository.findAllByDeleted(false);
            List<CalendarPreConditionLinkEntity> vinEntities = allEntities.stream()
                    .filter(e -> vin.equals(e.getVin()))
                    .collect(Collectors.toList());
                    
            List<Event> allEvents = googleCalendarService.getCalendar().getItems();
            smartCleanupPreconditioningSchedules(vin, vinEntities, allEvents);
            
            // Get fresh schedules after cleanup (force refresh since we just cleaned)
            VehicleData vehicleData = getCachedOrFreshVehicleData(vin, true);
            if (vehicleData == null) {
                log.error("Cannot get fresh vehicle data for VIN {} after cleanup", vin);
                entity.setStatus(PreconditioningStatus.FAILED);
                calendarPreConditionLinkRepository.save(entity);
                return false;
            }
            List<VehicleData.PreconditionSchedule> currentSchedules = safeGetPreconditionSchedules(vehicleData);
            
            // Schedule specific precondition (focused execution, not batch scheduling)
            int successCount = scheduleSpecificPrecondition(entity, allEvents, currentSchedules, vin, vinToEmailMap);
            
            return successCount > 0;
            
        } catch (Exception e) {
            log.error("Error during vehicle wake and scheduling for event {}: {}", entity.getCalendarId(), e.getMessage(), e);
            entity.setStatus(PreconditioningStatus.FAILED);
            calendarPreConditionLinkRepository.save(entity);
            return false;
        }
    }
    
    /**
     * Schedule verification task for failed preconditioning attempts
     */
    private void scheduleVerificationTask(String calendarId, Map<String, String> vinToEmailMap, int delayMinutes) {
        log.info("Scheduling verification task for event {} in {} minutes", calendarId, delayMinutes);
        
        ScheduledFuture<?> verificationTask = scheduler.schedule(() -> {
            TransactionTemplate tt = new TransactionTemplate(transactionManager);
            tt.executeWithoutResult(status -> {
                try {
                    executeVerificationTask(calendarId, vinToEmailMap);
                } catch (Exception e) {
                    log.error("Error during verification task for {}: {}", calendarId, e.getMessage(), e);
                }
            });
        }, delayMinutes, TimeUnit.MINUTES);
        
        scheduledVerificationTasks.put(calendarId, verificationTask);
    }
    
    /**
     * Execute verification task - check if schedule exists, wake and retry if not
     */
    private void executeVerificationTask(String calendarId, Map<String, String> vinToEmailMap) {
        Optional<CalendarPreConditionLinkEntity> entityOpt = calendarPreConditionLinkRepository.findFirstByCalendarId(calendarId);
        if (entityOpt.isEmpty()) {
            log.debug("Entity for calendar event {} no longer exists during verification", calendarId);
            return;
        }
        
        CalendarPreConditionLinkEntity entity = entityOpt.get();
        
        // CIRCUIT BREAKER: Check if verification is still needed
        if (entity.getStatus() == PreconditioningStatus.ACTIVE) {
            sendSmsNotification(buildPreconditioningConfirmationSms(entity));
            log.info("🔄 Entity {} already verified (likely during cleanup), skipping verification task", calendarId);
            return;
        }
        
        if (entity.getStatus() == PreconditioningStatus.EXPIRED || entity.isDeleted()) {
            log.info("🚫 Entity {} expired or deleted, cancelling verification task", calendarId);
            return;
        }
        
        if (entity.getPreconditionId() > 0) {
            log.info("🔗 Entity {} already has precondition ID {}, marking as active", calendarId, entity.getPreconditionId());
            entity.setStatus(PreconditioningStatus.ACTIVE);
            entity.setLastVerifiedTimestamp(System.currentTimeMillis());
            scheduleWeatherCheck(entity.getStoredPreconditionTime());
            sendSmsNotification(buildPreconditioningConfirmationSms(entity));
            calendarPreConditionLinkRepository.save(entity);
            return;
        }
        
        String vin = entity.getVin();
        log.debug("Proceeding with verification for entity {} (status: PENDING)", calendarId);
        
        try {
            // Check if schedule now exists on vehicle (use cached data for verification)
            VehicleData vehicleData = getCachedOrFreshVehicleData(vin, false);
            if (vehicleData != null) {
                List<VehicleData.PreconditionSchedule> schedules = safeGetPreconditionSchedules(vehicleData);
                
                // Check if our schedule exists (using stored parameters with tolerance)
                Integer storedTime = entity.getStoredPreconditionTime();
                
                // Debug logging for verification
                log.debug("Verification for event {}: stored time={}, checking {} schedules", 
                         calendarId, storedTime, schedules.size());
                
                boolean scheduleExists = false;
                if (storedTime != null) {
                    scheduleExists = schedules.stream()
                            .filter(VehicleData.PreconditionSchedule::getEnabled)
                            .filter(VehicleData.PreconditionSchedule::getOne_time)
                            .anyMatch(s -> Math.abs(s.getPrecondition_time() - storedTime) <= 5);
                } else {
                    // Fallback: check if any reasonable schedule exists for today
                    ZonedDateTime today = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(ZoneId.of("America/New_York"));
                    DayOfWeek todayDayOfWeek = today.getDayOfWeek();
                    
                    scheduleExists = schedules.stream()
                            .filter(VehicleData.PreconditionSchedule::getEnabled)
                            .filter(VehicleData.PreconditionSchedule::getOne_time)
                            .filter(schedule -> fleetApiService.decodeDaysOfWeek(schedule.getDays_of_week()).contains(todayDayOfWeek))
                            .anyMatch(s -> s.getPrecondition_time() > 0 && s.getPrecondition_time() < 1440); // Valid time range
                            
                    log.debug("Fallback verification used (no stored time) - found any reasonable schedule: {}", scheduleExists);
                }
                
                if (scheduleExists) {
                    log.info("Verification successful - preconditioning schedule found for event {}", calendarId);
                    entity.setStatus(PreconditioningStatus.ACTIVE);
                    // Update precondition ID if we can find it
                    schedules.stream()
                            .filter(VehicleData.PreconditionSchedule::getEnabled)
                            .filter(VehicleData.PreconditionSchedule::getOne_time)
                            .filter(s -> storedTime == null || Math.abs(s.getPrecondition_time() - storedTime) <= 5)
                            .findFirst()
                            .ifPresent(schedule -> entity.setPreconditionId(schedule.getId()));
                    scheduleWeatherCheck(entity.getStoredPreconditionTime());
                    sendSmsNotification(buildPreconditioningConfirmationSms(entity));
                    calendarPreConditionLinkRepository.save(entity);
                } else {
                    log.warn("Verification failed - retrying preconditioning scheduling for event {}", calendarId);
                    executeVehicleWakeAndScheduling(entity, vinToEmailMap);
                }
            } else {
                log.warn("Cannot verify preconditioning for event {} due to API rate limits", calendarId);
                // Schedule another verification for later
                scheduleVerificationTask(calendarId, vinToEmailMap, 30);
            }
        } catch (Exception e) {
            log.error("Error during verification for event {}: {}", calendarId, e.getMessage(), e);
        }
    }
    
    /**
     * Schedule up to 3 preconditioning entries for a VIN
     */
    private int scheduleMultiplePreconditions(CalendarPreConditionLinkEntity triggeringEntity, List<Event> allEvents, 
                                            List<VehicleData.PreconditionSchedule> currentSchedules, 
                                            String vin, Map<String, String> vinToEmailMap) {
        int successCount = 0;
        List<SchedulingTask> tasks = new ArrayList<>();
        
        // Priority 1: The triggering event (handle return home synthetic IDs)
        String baseCalendarId = triggeringEntity.getCalendarId().replace("_RETURN_HOME", "");
        Event triggeringEvent = allEvents.stream()
                .filter(e -> e.getId().equals(baseCalendarId))
                .findFirst()
                .orElse(null);
                
        if (triggeringEvent != null) {
            if (triggeringEntity.getCalendarId().contains("_RETURN_HOME")) {
                // This is a return home task - create return home scheduling task
                SchedulingTask returnTask = createReturnHomeSchedulingTask(triggeringEvent, triggeringEntity, vin);
                if (returnTask != null) {
                    tasks.add(returnTask);
                    log.debug("Added return home task for triggering entity {}", triggeringEntity.getCalendarId());
                } else {
                    log.info("Return home task deferred for {} - vehicle is still traveling", triggeringEntity.getCalendarId());
                    return 0; // Will be scheduled when vehicle becomes stationary
                }
            } else {
                // This is a regular departure task
                tasks.add(createSchedulingTask(triggeringEvent, triggeringEntity, vin, allEvents));
                log.debug("Added departure task for triggering entity {}", triggeringEntity.getCalendarId());
            }
        } else {
            log.warn("Could not find calendar event for triggering entity {}", triggeringEntity.getCalendarId());
        }
        
        // Priority 2-3: Only look for additional opportunities if triggered by departure event
        if (!triggeringEntity.getCalendarId().contains("_RETURN_HOME")) {
            List<CalendarPreConditionLinkEntity> allVinEntities = calendarPreConditionLinkRepository.findAllByDeleted(false)
                    .stream()
                    .filter(e -> vin.equals(e.getVin()))
                    .filter(e -> !e.getCalendarId().equals(triggeringEntity.getCalendarId()))
                    .collect(Collectors.toList());
                    
            for (CalendarPreConditionLinkEntity entity : allVinEntities) {
                if (tasks.size() >= 3) break;
                
                if (entity.getCalendarId().contains("_RETURN_HOME")) {
                    Event parentEvent = allEvents.stream()
                            .filter(e -> e.getId().equals(entity.getCalendarId().replace("_RETURN_HOME", "")))
                            .findFirst()
                            .orElse(null);
                            
                    if (parentEvent != null && shouldScheduleReturnHome(parentEvent, allEvents)) {
                        SchedulingTask returnTask = createReturnHomeSchedulingTask(parentEvent, entity, vin);
                        if (returnTask != null) {
                            tasks.add(returnTask);
                            log.debug("Added additional return home task for entity {}", entity.getCalendarId());
                        }
                        // If null, vehicle is traveling - will be scheduled when it becomes stationary
                    }
                }
            }
        } else {
            log.debug("Triggered by return home event - focusing only on that specific return home task");
        }
        
        // Execute the scheduling tasks
        for (SchedulingTask task : tasks) {
            if (executeSchedulingTask(task, currentSchedules, vin)) {
                successCount++;
            }
        }
        
        log.info("Successfully scheduled {} out of {} preconditions for VIN {}", successCount, tasks.size(), vin);
        return successCount;
    }
    
    /**
     * Schedule only the specific precondition for the triggering entity (focused execution)
     */
    private int scheduleSpecificPrecondition(CalendarPreConditionLinkEntity triggeringEntity, List<Event> allEvents, 
                                           List<VehicleData.PreconditionSchedule> currentSchedules, 
                                           String vin, Map<String, String> vinToEmailMap) {
        // Find the triggering event (handle return home synthetic IDs)
        String baseCalendarId = triggeringEntity.getCalendarId().replace("_RETURN_HOME", "");
        Event triggeringEvent = allEvents.stream()
                .filter(e -> e.getId().equals(baseCalendarId))
                .findFirst()
                .orElse(null);
                
        if (triggeringEvent == null) {
            log.error("Could not find calendar event for triggering entity {}", triggeringEntity.getCalendarId());
            return 0;
        }
        
        // Create and execute only the specific task for this entity
        SchedulingTask task;
        if (triggeringEntity.getCalendarId().contains("_RETURN_HOME")) {
            task = createReturnHomeSchedulingTask(triggeringEvent, triggeringEntity, vin);
            if (task == null) {
                // Vehicle is still traveling - defer until stationary
                log.info("Deferring return home preconditioning for {} - vehicle is still traveling", 
                        triggeringEntity.getCalendarId());
                return 0; // Will be scheduled when vehicle becomes stationary via checkPendingPreconditionsForLocationUpdate
            }
            log.info("Executing focused return home preconditioning for {}", triggeringEntity.getCalendarId());
        } else {
            task = createSchedulingTask(triggeringEvent, triggeringEntity, vin, allEvents);
            log.info("Executing focused departure preconditioning for {}", triggeringEntity.getCalendarId());
        }
        
        // Execute only this specific task
        boolean success = executeSchedulingTask(task, currentSchedules, vin);
        return success ? 1 : 0;
    }
    
    /**
     * Validate and adjust timing based on current vehicle location
     * Handles case where vehicle moved since task was originally scheduled
     * @return true if timing was adjusted due to location change
     */
    private boolean validateAndAdjustTimingForCurrentLocation(CalendarPreConditionLinkEntity entity, Event event, String vin) {
        try {
            // Get fresh current vehicle location for accurate movement detection
            log.debug("Validating vehicle location for preconditioning decisions (forcing fresh data)");
            double[] currentLocation = getCurrentVehicleLocation(vin); // This already forces fresh data
            double currentLat = currentLocation[0];
            double currentLon = currentLocation[1];
            
            // Calculate distance from originally assumed location
            boolean useCurrentLocation = shouldUseCurrentLocationForScheduling(vin, event);
            double originalLat = useCurrentLocation ? entity.getStoredLatitude() != null ? entity.getStoredLatitude() : homeLatitude : homeLatitude;
            double originalLon = useCurrentLocation ? entity.getStoredLongitude() != null ? entity.getStoredLongitude() : homeLongitude : homeLongitude;
            
            double locationChangeDistance = calculateDistance(currentLat, currentLon, originalLat, originalLon);
            
            // If vehicle moved more than 0.1 miles, recalculate timing
            if (locationChangeDistance > 0.1) {
                log.info("Vehicle moved {} miles from expected location, recalculating preconditioning timing", locationChangeDistance);
                
                // Recalculate route from current location
                int newMinutesToDestination = 15; // Default
                try {
                    newMinutesToDestination = Math.toIntExact(routesCalculationService
                            .calculateRouteToDestination(currentLat, currentLon, event.getLocation())
                            .getRoutesList().stream().max(Comparator.comparing(r -> r.getDuration().getSeconds()))
                            .get().getDuration().getSeconds() / 60);
                } catch (Exception e) {
                    log.warn("Failed to recalculate route for moved vehicle, using default: {}", e.getMessage());
                }
                
                // Calculate new desired preconditioning time
                ZonedDateTime eventTime = Instant.ofEpochMilli(event.getStart().getDateTime().getValue())
                        .atZone(ZoneId.of("America/New_York"));
                int minutesFromMidnight = eventTime.getHour() * 60 + eventTime.getMinute();
                int newDesiredTime = minutesFromMidnight - newMinutesToDestination - preconditioningBufferMinutes;
                
                // Check if timing changed significantly (>5 minutes)
                Integer oldDesiredTime = entity.getStoredPreconditionTime();
                if (oldDesiredTime == null || Math.abs(newDesiredTime - oldDesiredTime) > 5) {
                    log.warn("Preconditioning timing changed significantly due to vehicle movement: {} -> {} minutes from midnight", 
                            oldDesiredTime, newDesiredTime);
                    
                    // Update stored parameters with new location and timing
                    smartScheduleManager.updateStoredParameters(entity, newDesiredTime, 
                            eventTime.getDayOfWeek(), currentLat, currentLon);
                    
                    // Check if we're now too late to precondition properly (more forgiving for return home)
                    ZonedDateTime now = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(ZoneId.of("America/New_York"));
                    int currentMinutesFromMidnight = now.getHour() * 60 + now.getMinute();
                    int minutesUntilPreconditioning = newDesiredTime - currentMinutesFromMidnight;
                    
                    // Handle day rollover
                    if (minutesUntilPreconditioning < 0) {
                        minutesUntilPreconditioning += 24 * 60;
                    }
                    
                    // Be more forgiving for return home events - allow up to event start time
                    int minimumTimeMinutes = entity.getCalendarId().contains("_RETURN_HOME") ? 0 : 10;
                    
                    if (minutesUntilPreconditioning < minimumTimeMinutes) {
                        if (entity.getCalendarId().contains("_RETURN_HOME")) {
                            log.info("Return home preconditioning timing very tight ({} min), but proceeding anyway", minutesUntilPreconditioning);
                        } else {
                            log.warn("Vehicle movement caused timing conflict - too late for proper preconditioning for event {}", entity.getCalendarId());
                            entity.setStatus(PreconditioningStatus.FAILED);
                            calendarPreConditionLinkRepository.save(entity);
                            return false;
                        }
                    }
                    
                    return true; // Timing was adjusted
                }
            }
            
            return false; // No significant change
            
        } catch (Exception e) {
            log.warn("Failed to validate vehicle location for event {}: {}", entity.getCalendarId(), e.getMessage());
            return false;
        }
    }

    /**
     * Check if an entity should be scheduled based on the 1-hour rule
     */
    private boolean shouldScheduleBasedOnOneHourRule(CalendarPreConditionLinkEntity entity, List<Event> allEvents) {
        // Skip if not PENDING or FAILED status
        if (entity.getStatus() != PreconditioningStatus.PENDING && 
            entity.getStatus() != PreconditioningStatus.FAILED) {
            return false;
        }
        
        // Find the corresponding event
        Event event = allEvents.stream()
                .filter(e -> e.getId().equals(entity.getCalendarId()))
                .findFirst()
                .orElse(null);
                
        if (event == null) {
            return false;
        }
        
        // Calculate desired preconditioning time
        ZonedDateTime eventTime = Instant.ofEpochMilli(entity.getUnixStartTime())
                .atZone(ZoneId.of("America/New_York"));
        int minutesFromMidnight = eventTime.getHour() * 60 + eventTime.getMinute();
        
        // Get route duration (use cached calculation if available)
        int minutesToDestination = 15; // Default
        try {
            boolean useCurrentLocation = shouldUseCurrentLocationForScheduling(entity.getVin(), event);
            double startLat = useCurrentLocation ? getCurrentVehicleLocation(entity.getVin())[0] : homeLatitude;
            double startLon = useCurrentLocation ? getCurrentVehicleLocation(entity.getVin())[1] : homeLongitude;
            
            minutesToDestination = Math.toIntExact(routesCalculationService
                    .calculateRouteToDestination(startLat, startLon, event.getLocation())
                    .getRoutesList().stream().max(Comparator.comparing(r -> r.getDuration().getSeconds()))
                    .get().getDuration().getSeconds() / 60);
        } catch (Exception e) {
            log.debug("Failed to calculate route for 1-hour check, using default: {}", e.getMessage());
        }
        
        int desiredPreConditioningTime = minutesFromMidnight - minutesToDestination - preconditioningBufferMinutes;
        
        // Check if we're in the 1-hour window
        return shouldScheduleEventNow(event, desiredPreConditioningTime, 5);
    }
    
    /**
     * Track a calendar event (create or update entity) without immediate vehicle scheduling
     * @return The tracked entity, or null if event couldn't be tracked
     */
    private CalendarPreConditionLinkEntity trackCalendarEvent(Event event, Map<String, String> emailToVinMap, List<Event> allEvents) {
        Optional<CalendarPreConditionLinkEntity> existingEntity = calendarPreConditionLinkRepository.findFirstByCalendarId(event.getId());
        CalendarPreConditionLinkEntity entityToProcess;
        
        // Handle duplicate entries
        List<CalendarPreConditionLinkEntity> duplicateEntities = calendarPreConditionLinkRepository
                .findAllByCalendarIdAndDeleted(event.getId(), false);
        if (duplicateEntities.size() > 1) {
                log.warn("Found {} duplicate entries for calendar event {}, keeping most recent", 
                        duplicateEntities.size(), event.getId());
                // Keep the most recent entity, mark others as deleted
                CalendarPreConditionLinkEntity mostRecent = duplicateEntities.stream()
                        .max(Comparator.comparing(CalendarPreConditionLinkEntity::getUnixStartTime))
                        .orElse(duplicateEntities.get(0));
                duplicateEntities.stream()
                        .filter(entity -> !entity.equals(mostRecent))
                        .forEach(entity -> entity.setDeleted(true));
                calendarPreConditionLinkRepository.saveAll(duplicateEntities);
                existingEntity = Optional.of(mostRecent);
        }
        
        if (existingEntity.isPresent()) {
            entityToProcess = existingEntity.get();
            
            // Update event time if it has changed
            if (entityToProcess.getUnixStartTime() != event.getStart().getDateTime().getValue()) {
                java.util.Date oldTime = new java.util.Date(entityToProcess.getUnixStartTime());
                java.util.Date newTime = new java.util.Date(event.getStart().getDateTime().getValue());
                
                log.info("Event {} start time changed from {} to {} - cancelling task and forcing reschedule", 
                        event.getId(), oldTime, newTime);
                
                // Cancel existing scheduled task since timing has changed
                cancelExistingTasks(event.getId());
                
                // Update time and reset status
                entityToProcess.setUnixStartTime(event.getStart().getDateTime().getValue());
                entityToProcess.setStatus(PreconditioningStatus.PENDING); // Reset for re-evaluation
                
                // Send SMS notification about time change
                String eventName = event.getSummary() != null ? event.getSummary() : "Unknown Event";
                java.text.SimpleDateFormat timeFormat = new java.text.SimpleDateFormat("h:mm a");
                sendSmsNotification(String.format("Event '%s' time changed from %s to %s - preconditioning will be rescheduled",
                        eventName, timeFormat.format(oldTime), timeFormat.format(newTime)));
            }
            
            // Update event summary if it has changed
            if (event.getSummary() != null && !event.getSummary().equals(entityToProcess.getEventSummary())) {
                entityToProcess.setEventSummary(event.getSummary());
            }
            
            // Skip if already active and not expired
            if (entityToProcess.getStatus() == PreconditioningStatus.ACTIVE &&
                System.currentTimeMillis() < entityToProcess.getUnixStartTime()) {
                return entityToProcess; // Already properly scheduled
            }
        } else {
            // New event - create entity
            entityToProcess = new CalendarPreConditionLinkEntity();
            entityToProcess.setCalendarId(event.getId());
            entityToProcess.setUnixStartTime(event.getStart().getDateTime().getValue());
            entityToProcess.setStatus(PreconditioningStatus.PENDING);
            entityToProcess.setEventSummary(event.getSummary());
        }
        
        // Determine email and VIN assignment
        String emailToAssign = determineEventAssignee(event);
        if (emailToAssign == null) {
        log.info("No valid assignee found for calendar event: {}, skipping", event.getId());
        return null;
        }

        String newVin = emailToVinMap.get(emailToAssign);
        String oldVin = entityToProcess.getVin();
        
        // Detect VIN change (assignee changed)
        boolean vinChanged = oldVin != null && !oldVin.equals(newVin);
        if (vinChanged) {
            log.warn("Event {} assignee changed from VIN {} ({}) to VIN {} ({}) - cancelling old task and forcing reschedule",
                    event.getId(), oldVin, getVehicleDisplayName(oldVin), newVin, getVehicleDisplayName(newVin));
            
            // Cancel existing scheduled task
            cancelExistingTasks(event.getId());
            
            // Reset status to PENDING to force re-evaluation and rescheduling
            entityToProcess.setStatus(PreconditioningStatus.PENDING);
            
            // Send SMS notification about the change
            String eventName = event.getSummary() != null ? event.getSummary() : "Unknown Event";
            sendSmsNotification(String.format("Event assignee changed: '%s' reassigned from %s to %s", 
                    eventName, getVehicleDisplayName(oldVin), getVehicleDisplayName(newVin)));
        }
        
        // Update entity
        entityToProcess.setAttendeeEmail(emailToAssign);
        entityToProcess.setVin(newVin);
        
        // Save entity with PENDING status for later 1-hour evaluation
        calendarPreConditionLinkRepository.save(entityToProcess);
        log.debug("Tracked calendar event {} for VIN {} (status: {})", event.getId(), newVin, entityToProcess.getStatus());
        
        return entityToProcess;
    }
    
    /**
    * Determine the assignee email for an event based on attendees or creator
    */
    private String determineEventAssignee(Event event) {
    List<String> emailsAndVins = Arrays.stream(vinCsv.split(",")).toList();
        List<String> allEmails = emailsAndVins.stream().map(s -> s.split(":")[0]).toList();

    String emailToAssign = null;

    // Check attendees first
    if (event.getAttendees() != null) {
    List<EventAttendee> validAttendees = event.getAttendees().stream()
        .filter(attendee -> allEmails.contains(attendee.getEmail()))
            .toList();
        if (validAttendees.size() == 1) {
                emailToAssign = validAttendees.get(0).getEmail();
        }
    }

    // Fall back to creator if no single valid attendee
    if (emailToAssign == null && event.getCreator() != null) {
        if (allEmails.contains(event.getCreator().getEmail())) {
                emailToAssign = event.getCreator().getEmail();
        }
        }

        return emailToAssign;
    }
    
    /**
     * Schedule return home preconditioning for events that don't have subsequent events at the same location
     */
    @Transactional
    public void scheduleReturnHomePreconditioning(List<Event> processedEvents, List<Event> allEvents, Map<String, String> emailToVinMap) throws InterruptedException {
    // Build vinToEmailMap for compatibility
    Map<String, String> vinToEmailMap = emailToVinMap.entrySet().stream()
        .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey, (a,b) -> a));

        log.info("Checking for return home preconditioning opportunities");

        for (Event event : processedEvents) {
            if (event.getLocation() == null || event.getEnd() == null || event.getEnd().getDateTime() == null) {
                continue;
            }
            
            // Find an assignee for this event
            String assigneeEmail = determineEventAssignee(event);
            if (assigneeEmail == null) {
                continue;
            }

            String vin = emailToVinMap.get(assigneeEmail);
            
            // Check if we should schedule return home for this event
            if (shouldScheduleReturnHome(event, processedEvents)) {
                String returnHomeCalendarId = event.getId() + "_RETURN_HOME";
                
                // Check if we already have a return home entry
                Optional<CalendarPreConditionLinkEntity> existingEntity = 
                        calendarPreConditionLinkRepository.findFirstByCalendarId(returnHomeCalendarId);
                
                CalendarPreConditionLinkEntity returnEntity;
                if (existingEntity.isPresent()) {
                    returnEntity = existingEntity.get();
                    // Skip if already active or if we already have a scheduled task
                    if (returnEntity.getStatus() == PreconditioningStatus.ACTIVE) {
                        continue;
                    }
                    
                    // Check if we already have an event start check task scheduled
                    String startCheckTaskKey = returnHomeCalendarId + "_START_CHECK";
                    if (scheduledPreconditioningTasks.containsKey(startCheckTaskKey)) {
                        ScheduledFuture<?> existingTask = scheduledPreconditioningTasks.get(startCheckTaskKey);
                        if (existingTask != null && !existingTask.isCancelled() && !existingTask.isDone()) {
                            log.debug("Event start check task already scheduled for {}, skipping duplicate", returnHomeCalendarId);
                            continue;
                        } else {
                            // Remove stale task reference
                            scheduledPreconditioningTasks.remove(startCheckTaskKey);
                        }
                    }
                } else {
                    // Create a new return home entity
                    returnEntity = new CalendarPreConditionLinkEntity();
                    returnEntity.setCalendarId(returnHomeCalendarId);
                    returnEntity.setUnixStartTime(event.getEnd().getDateTime().getValue());
                    returnEntity.setStatus(PreconditioningStatus.PENDING);
                    returnEntity.setAttendeeEmail(assigneeEmail);
                    returnEntity.setVin(vin);
                    returnEntity.setEventSummary(event.getSummary() != null ? event.getSummary() + " (Return Home)" : "Return Home");
                    
                    // Store initial parameters for return home timing (will be updated when event starts)
                    ZonedDateTime returnTime = Instant.ofEpochMilli(event.getEnd().getDateTime().getValue())
                            .atZone(ZoneId.of("America/New_York"));
                    int minutesFromMidnight = returnTime.getHour() * 60 + returnTime.getMinute();
                    int initialDesiredTime = minutesFromMidnight; // No buffer for return home
                    
                    smartScheduleManager.updateStoredParameters(returnEntity, initialDesiredTime, 
                            returnTime.getDayOfWeek(), homeLatitude, homeLongitude);
                    log.debug("Stored initial return home parameters for {}: time={}, day={}", 
                             returnHomeCalendarId, initialDesiredTime, returnTime.getDayOfWeek());
                }
                
                calendarPreConditionLinkRepository.save(returnEntity);
                log.debug("Tracked return home event for {}", event.getId());
                
                // Schedule task to check if event has started and then schedule return home preconditioning
                scheduleEventStartCheckTask(event, returnEntity, vinToEmailMap);
            }
        }
    }
    
    /**
     * Check if a return home preconditioning should be scheduled for an event
     */
    private boolean shouldScheduleReturnHome(Event event, List<Event> allEvents) {
        if (event.getEnd() == null || event.getEnd().getDateTime() == null || 
            event.getLocation() == null || event.getLocation().trim().isEmpty()) {
            return false;
        }
        
        List<String> emailsAndVins = Arrays.stream(vinCsv.split(",")).toList();
        Map<String, String> emailToVin = emailsAndVins.stream()
            .collect(Collectors.toMap(s -> s.split(":")[0], s -> s.split(":")[1], (a,b) -> a));

        // Find next event for same person after current event ends
        String assigneeEmail = determineEventAssignee(event);
        if (assigneeEmail == null) return false;

        long eventEndTime = event.getEnd().getDateTime().getValue();
        Optional<Event> nextEvent = allEvents.stream()
                .filter(e -> e.getStart().getDateTime() != null)
                .filter(e -> e.getStart().getDateTime().getValue() > eventEndTime)
                .filter(e -> assigneeEmail.equals(determineEventAssignee(e)))
                .min(Comparator.comparing(e -> e.getStart().getDateTime().getValue()));
        
        // Schedule return home if there's no next event within 4 hours
        if (nextEvent.isEmpty() || nextEvent.get().getLocation() == null) {
            return true; // No subsequent events, definitely need return home
        }
        
        long timeBetweenEvents = nextEvent.get().getStart().getDateTime().getValue() - eventEndTime;
        long twoHoursMs = 2 * 60 * 60 * 1000L;
        return (!nextEvent.get().getLocation().equalsIgnoreCase(event.getLocation())) || timeBetweenEvents >= twoHoursMs; // More than a 4-hour gap
    }
    
    /**
     * Get cached vehicle data or fetch fresh if needed, with proper API tracking
     * @param vin Vehicle identification number
     * @param forceRefresh True to bypass cache (use after vehicle modifications or for location decisions)
     * @return VehicleData or null if unavailable
     */
    private VehicleData getCachedOrFreshVehicleData(String vin, boolean forceRefresh) throws InterruptedException {
        if (!forceRefresh) {
            // Try cache first for read-only operations
            VehicleData cachedData = vehicleDataCache.getCachedData(vin);
            if (cachedData != null) {
                // Check cache age - invalidate if older than 4 hours
                long cacheAge = vehicleDataCache.getCacheAge(vin);
                long fourHoursMs = 4 * 60 * 60 * 1000L;
                
                if (cacheAge > fourHoursMs) {
                    log.debug("Cache expired for VIN {} (age: {} hours), fetching fresh data", 
                             vin, cacheAge / (60 * 60 * 1000L));
                    invalidateVehicleDataCache(vin, "cache expired (>4 hours)");
                } else {
                    log.debug("Using cached vehicle data for VIN {} (age: {} minutes)", 
                             vin, cacheAge / (60 * 1000L));
                    return cachedData;
                }
            }
        }
        
        // Need fresh data
        if (apiUsageTracker.canExecuteCommand(vin, VehicleApiUsageTracker.CommandType.GET_DATA)) {
            VehicleData freshData = fleetApiService.getVehicleDataNoCache(vin);
            apiUsageTracker.recordCommand(vin, VehicleApiUsageTracker.CommandType.GET_DATA);
            extractVehicleNameFromApiResponse(vin, freshData);
            
            // Cache the fresh data
            vehicleDataCache.cacheVehicleData(vin, freshData, false);
            log.debug("Fetched and cached fresh vehicle data for {}", getVehicleDisplayName(vin));
            return freshData;
        } else {
            log.debug("Cannot fetch vehicle data for {} due to API rate limits", getVehicleDisplayName(vin));
            return null;
        }
    }
    
    /**
     * Invalidate vehicle data cache after vehicle modifications
     * Call this after adding/removing schedules, starting climate, etc.
     */
    private void invalidateVehicleDataCache(String vin, String reason) {
        vehicleDataCache.clearCache(vin);
        log.debug("Invalidated vehicle data cache for VIN {} - reason: {}", vin, reason);
    }
    
    /**
     * Schedule a task to check when event starts, then schedule return home preconditioning
     */
    private synchronized void scheduleEventStartCheckTask(Event parentEvent, CalendarPreConditionLinkEntity returnEntity, Map<String, String> vinToEmailMap) {
        long eventStartTime = parentEvent.getStart().getDateTime().getValue();
        long currentTime = System.currentTimeMillis();
        
        if (eventStartTime <= currentTime) {
            // Event has already started - schedule return home task immediately
            log.info("Event {} already started, scheduling return home task now", parentEvent.getId());
            scheduleReturnHomeTaskAfterEventStart(parentEvent, returnEntity, vinToEmailMap);
            return;
        }
        
        // Check if we already have an event start check task scheduled
        String startCheckTaskKey = returnEntity.getCalendarId() + "_START_CHECK";
        if (scheduledPreconditioningTasks.containsKey(startCheckTaskKey)) {
            ScheduledFuture<?> existingTask = scheduledPreconditioningTasks.get(startCheckTaskKey);
            if (existingTask != null && !existingTask.isCancelled() && !existingTask.isDone()) {
                log.debug("Event start check task already scheduled for {}, skipping duplicate", returnEntity.getCalendarId());
                return;
            } else {
                // Remove stale task reference
                scheduledPreconditioningTasks.remove(startCheckTaskKey);
            }
        }
        
        // Schedule task to execute when event starts
        long delayMs = eventStartTime - currentTime;
        log.info("Scheduling event start check for {} in {} minutes (return home will be scheduled then)", 
                parentEvent.getId(), delayMs / (60 * 1000));
        
        ScheduledFuture<?> eventStartTask = scheduler.schedule(() -> {
            TransactionTemplate tt = new TransactionTemplate(transactionManager);
            tt.executeWithoutResult(status -> {
                try {
                    // Re-fetch entity to ensure it still exists
                    Optional<CalendarPreConditionLinkEntity> entityOpt = 
                            calendarPreConditionLinkRepository.findFirstByCalendarId(returnEntity.getCalendarId());
                    if (entityOpt.isPresent() && !entityOpt.get().isDeleted()) {
                        log.info("Event {} has started, now scheduling return home preconditioning task", parentEvent.getId());
                        scheduleReturnHomeTaskAfterEventStart(parentEvent, entityOpt.get(), vinToEmailMap);
                    }
                } catch (Exception e) {
                    log.error("Error during event start check for {}: {}", parentEvent.getId(), e.getMessage(), e);
                }
            });
        }, delayMs, TimeUnit.MILLISECONDS);
        
        // Track this task for cancellation if event is deleted
        scheduledPreconditioningTasks.put(returnEntity.getCalendarId() + "_START_CHECK", eventStartTask);
    }
    
    /**
     * Schedule return home preconditioning task after event has started (vehicle at destination)
     */
    private synchronized void scheduleReturnHomeTaskAfterEventStart(Event parentEvent, CalendarPreConditionLinkEntity returnEntity, Map<String, String> vinToEmailMap) {
        // Calculate when to schedule the return home task (1 hour before return preconditioning)
        long scheduleTaskTime = calculateReturnHomeTaskTime(parentEvent, returnEntity);
        if (scheduleTaskTime == -1) {
            log.debug("Event {} has ended, no return home task needed", parentEvent.getId());
            return;
        }
        
        String vin = returnEntity.getVin();
        String eventName = returnEntity.getEventSummary() != null ? returnEntity.getEventSummary() : "Unknown Event";
        
        // Check if vehicle is still near home (within 0.25 miles) - if so, skip return home preconditioning
        double[] currentLocation = getCurrentVehicleLocation(vin);
        double distanceFromHome = calculateDistance(currentLocation[0], currentLocation[1], homeLatitude, homeLongitude);
        if (distanceFromHome <= 0.25) {
            // Vehicle is still at home - check if it was ever away
            Boolean wasAway = returnEntity.getWasVehicleAway();
            if (wasAway == null || !wasAway) {
                log.info("Skipping return home preconditioning for {} - {} is within 0.25 miles of home and never left",
                        returnEntity.getCalendarId(), getVehicleDisplayName(vin));
                returnEntity.setStatus(PreconditioningStatus.EXPIRED);
                returnEntity.setDeleted(true);
                calendarPreConditionLinkRepository.save(returnEntity);
                sendSmsNotification("Return home preconditioning skipped for " + getVehicleDisplayName(vin) + 
                        " - vehicle is already home (event: '" + eventName + "')");
                return;
            }
            // Vehicle was away and returned - this is expected for return home
            log.info("{} is back home after being away - proceeding with return home preconditioning for {}",
                    getVehicleDisplayName(vin), returnEntity.getCalendarId());
        } else {
            // Vehicle is away from home - mark it and proceed with scheduling
            if (returnEntity.getWasVehicleAway() == null || !returnEntity.getWasVehicleAway()) {
                returnEntity.setWasVehicleAway(true);
                calendarPreConditionLinkRepository.save(returnEntity);
                log.info("{} is away from home ({} miles), return home preconditioning will be scheduled for {}",
                        getVehicleDisplayName(vin), String.format("%.2f", distanceFromHome), returnEntity.getCalendarId());
            }
        }
        
        // Check if we already have a task scheduled for this return home entry
        if (scheduledPreconditioningTasks.containsKey(returnEntity.getCalendarId())) {
            ScheduledFuture<?> existingTask = scheduledPreconditioningTasks.get(returnEntity.getCalendarId());
            if (existingTask != null && !existingTask.isCancelled() && !existingTask.isDone()) {
                log.debug("Return home task already scheduled for {}, skipping duplicate", returnEntity.getCalendarId());
                return;
            }
        }
        
        // Cancel any existing task for this return home entry (redundant but kept for safety)
        cancelExistingTasks(returnEntity.getCalendarId());
        
        // Schedule the return home task
        long delayMs = scheduleTaskTime - System.currentTimeMillis();
        log.info("Scheduling return home task for event {} in {} minutes (vehicle now at destination)", 
                parentEvent.getId(), delayMs / (60 * 1000));
        
        ScheduledFuture<?> scheduledTask = scheduler.schedule(() -> {
            executeScheduledPreconditioningTask(returnEntity.getCalendarId(), vinToEmailMap);
        }, delayMs, TimeUnit.MILLISECONDS);
        
        // Track the scheduled task for potential cancellation
        scheduledPreconditioningTasks.put(returnEntity.getCalendarId(), scheduledTask);
    }
    
    /**
     * Calculate when to schedule the return home task (1 hour before return preconditioning time)
     */
    private long calculateReturnHomeTaskTime(Event parentEvent, CalendarPreConditionLinkEntity returnEntity) {
        long currentTime = System.currentTimeMillis();
        long eventEndTime = parentEvent.getEnd().getDateTime().getValue();
        
        // If the event has already ended, no return home needed
        if (currentTime >= eventEndTime) {
            log.debug("Event {} has already ended, no return home needed", parentEvent.getId());
            return -1; // Signal that no task should be scheduled
        }
        
        ZonedDateTime returnTime = Instant.ofEpochMilli(eventEndTime).atZone(ZoneId.of("America/New_York"));
        int minutesFromMidnight = returnTime.getHour() * 60 + returnTime.getMinute();
        
        // For return home: car should be ready exactly at meeting END time (no buffer)
        int desiredPreconditionTime = minutesFromMidnight;
        
        // Calculate when preconditioning should start for THIS event occurrence
        ZonedDateTime preconditionStart = returnTime.toLocalDate()
                .atTime(desiredPreconditionTime / 60, desiredPreconditionTime % 60)
                .atZone(ZoneId.of("America/New_York"));
        
        // Calculate task time (1 hour before preconditioning)
        long taskTime = preconditionStart.toEpochSecond() * 1000 - (60 * 60 * 1000L);
        
        // If task time has passed but event hasn't ended, schedule immediately
        if (taskTime <= currentTime && currentTime < eventEndTime) {
            log.info("Return home task time passed but event still active - scheduling immediately for event {}", parentEvent.getId());
            if (taskTime - currentTime > (30 * 60 * 1000L)) {
                return currentTime + ((taskTime - currentTime) / 2);
            }
            return currentTime + (10 * 60 * 1000L); // Schedule 5 minutes from now
        }
        
        return taskTime;
    }

    public void scheduleCheckCompletion(Event event,
                                        CalendarPreConditionLinkEntity calendarPreConditionLinkEntity,
                                        String vin,
                                        Integer desiredPreConditioningTime,
                                        DayOfWeek dayOfWeek) {
        scheduler.schedule(() -> {
            TransactionTemplate tt = new TransactionTemplate(transactionManager);
            // Use a new transaction so this runs independently of the caller
            tt.executeWithoutResult(status -> {
                try {
                    // Re-fetch entity to avoid optimistic locking issues
                    Optional<CalendarPreConditionLinkEntity> freshEntity = 
                        calendarPreConditionLinkRepository.findFirstByCalendarId(event.getId());
                    if (freshEntity.isPresent()) {
                        checkCompletionInternal(event, freshEntity.get(), vin, desiredPreConditioningTime, dayOfWeek);
                    } else {
                        log.warn("Entity for event {} no longer exists during completion check", event.getId());
                    }
                } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
                    log.warn("Optimistic locking failure during completion check for event {} - entity was updated by another transaction", event.getId());
                } catch (Exception e) {
                    log.error("Failed during delayed checkCompletion for calendar event: {}", event.getId(), e);
                }
            });
        }, completionDelayMinutes, TimeUnit.MINUTES);
    }

    public void scheduleWeatherCheck(long minutesIntoDay) {
        ZonedDateTime time = Instant.now().atZone(ZoneId.of("America/New_York"));
        long currentMinutes = time.getHour() * 60 + time.getMinute();
        long minutesUntilPrecon = minutesIntoDay - currentMinutes;
        long desiredDefrostCheckDelay = minutesUntilPrecon - 15;
        scheduler.schedule(() -> {
            TransactionTemplate tt = new TransactionTemplate(transactionManager);
            // Use a new transaction so this runs independently of the caller
            tt.executeWithoutResult(status -> {
                try {
                    weatherCheckInternal();
                } catch (Exception e) {
                }
            });
        }, desiredDefrostCheckDelay, TimeUnit.MINUTES);
    }

    public void scheduleDefrost(String vin, Long minutesUntilDefrost) {
        log.info("Scheduling defrost for {} in {} minutes", getVehicleDisplayName(vin), minutesUntilDefrost);
        vinDefrostMap.put(vin, System.currentTimeMillis() + (1000L * 60 * minutesUntilDefrost));
        scheduler.schedule(() -> {
            boolean defrostScheduled = false;
            
            // Check API rate limits before defrost
            if (apiUsageTracker.canExecuteCommand(vin, VehicleApiUsageTracker.CommandType.START_DEFROST)) {
                try {
                    if (wakeVehicleWithProperTracking(vin, 2)) {
                        defrostScheduled = fleetApiService.startMaxDefrost(vin);
                        apiUsageTracker.recordCommand(vin, VehicleApiUsageTracker.CommandType.START_DEFROST);
                        // Invalidate cache after starting defrost
                        invalidateVehicleDataCache(vin, "started defrost");
                    }
                } catch (InterruptedException e) {
                    log.error("Interrupted while scheduling defrost for VIN {}", vin, e);
                }
            } else {
                log.warn("Cannot start defrost for VIN {} due to API rate limits, defrost deferred", vin);
            }
            
            if (defrostScheduled) {
                log.info("Successfully started defrost for {}", getVehicleDisplayName(vin));
                sendSmsNotification("Defrost started for " + getVehicleDisplayName(vin));
            } else {
                log.info("Failed to start defrost for {}", getVehicleDisplayName(vin));
            }
            vinDefrostMap.remove(vin);
        }, minutesUntilDefrost, TimeUnit.MINUTES);
    }

    private void checkCompletionInternal(Event event, CalendarPreConditionLinkEntity calendarPreConditionLinkEntity, String vin, Integer desiredPreConditioningTime, DayOfWeek dayOfWeek) throws InterruptedException {
        // Check if event has passed or vehicle is driving - mark as expired if so
        long eventStartTime = event.getStart().getDateTime().getValue();
        if (System.currentTimeMillis() > eventStartTime) {
            calendarPreConditionLinkEntity.setStatus(PreconditioningStatus.EXPIRED);
            calendarPreConditionLinkRepository.save(calendarPreConditionLinkEntity);
            return;
        }
        
        Optional<VehicleData.PreconditionSchedule> completedSchedule = Optional.empty();
        
        // Use cached data for completion check since we're not modifying anything
        VehicleData vehicleData = getCachedOrFreshVehicleData(vin, false);
        if (vehicleData != null) {
            List<VehicleData.PreconditionSchedule> schedules = safeGetPreconditionSchedules(vehicleData);
            
            completedSchedule = schedules.stream()
                    .filter(VehicleData.PreconditionSchedule::getEnabled)
                    .filter(VehicleData.PreconditionSchedule::getOne_time)
                    .filter(schedule -> Math.abs(schedule.getPrecondition_time() - desiredPreConditioningTime) <= 5)
                    .filter(schedule -> fleetApiService.decodeDaysOfWeek(schedule.getDays_of_week()).contains(dayOfWeek))
                    .findFirst();
                    
            // Debug logging to help diagnose verification failures
            if (completedSchedule.isEmpty() && !schedules.isEmpty()) {
                log.debug("Verification debugging for event {}: desired time {}, day {}", 
                         event.getId(), desiredPreConditioningTime, dayOfWeek);
                schedules.stream()
                        .filter(VehicleData.PreconditionSchedule::getEnabled)
                        .filter(VehicleData.PreconditionSchedule::getOne_time)
                        .forEach(schedule -> {
                            int timeDiff = Math.abs(schedule.getPrecondition_time() - desiredPreConditioningTime);
                            Set<DayOfWeek> scheduleDays = fleetApiService.decodeDaysOfWeek(schedule.getDays_of_week());
                            log.debug("Found schedule: ID={}, time={} (diff={}min), days={}, matches_day={}", 
                                     schedule.getId(), schedule.getPrecondition_time(), timeDiff, 
                                     scheduleDays, scheduleDays.contains(dayOfWeek));
                        });
            }
        } else {
            log.warn("Cannot check completion for event {} due to API rate limits for VIN {}", event.getId(), vin);
        }
        
        if (completedSchedule.isPresent()) {
            // Successfully found the schedule - mark as active
            calendarPreConditionLinkEntity.setPreconditionId(completedSchedule.get().getId());
            calendarPreConditionLinkEntity.setStatus(PreconditioningStatus.ACTIVE);
            // Store partial parameters (time and day) to help avoid future queries
            calendarPreConditionLinkEntity.setStoredPreconditionTime(desiredPreConditioningTime);
            calendarPreConditionLinkEntity.setStoredDayOfWeek(dayOfWeek.name());
            calendarPreConditionLinkEntity.setLastVerifiedTimestamp(System.currentTimeMillis());
            log.info("Successfully activated preconditioning entry for calendar event: {}", event.getId());
        } else {
            // Tesla API didn't return the expected schedule - mark as failed for retry
            calendarPreConditionLinkEntity.setStatus(PreconditioningStatus.FAILED);
            log.info("Failed to find preconditioning entry for calendar event: {} - will retry", event.getId());
        }
        calendarPreConditionLinkRepository.save(calendarPreConditionLinkEntity);
    }

    public void weatherCheckInternal() throws IOException {
        List<CalendarPreConditionLinkEntity> entities = calendarPreConditionLinkRepository.findAllByDeleted(false);
        entities = List.of(entities.stream().filter(e ->
                System.currentTimeMillis() - e.getStoredPreconditionTime() < (1000 * 60 * 30)).findFirst().get());
        if (entities.isEmpty()) {
            return;
        } else {
            log.info("Running weather check for {} calendar entries", entities.size());
            boolean highRisk = weatherService.hasHighSnowOrIceCoverageLast12h(homeLatitude, homeLongitude, Optional.empty());
            if (highRisk) {
                log.info("High risk of snow accumulation or ice cover, scheduling a defrost for {} entries", entities.size());
                List<CalendarPreConditionLinkEntity> finalEntities = entities;
                List<Event> events = googleCalendarService.getCalendar().getItems().stream().filter(c -> finalEntities.stream().map(x -> x.getCalendarId()).toList().contains(c.getId())).toList();
                Map<String, Double> vinExteriorTemperatureMap = new HashMap<>();
                for (Event event : events) {
                    if (event.getLocation() == null) continue;
                    Optional<CalendarPreConditionLinkEntity> entity = calendarPreConditionLinkRepository.findFirstByCalendarId(event.getId());
                    if (!entity.isPresent()) {
                        continue;
                    }
                    
                    String vin = entity.get().getVin();
                    Double exteriorTemperature = vinOutsideTempMap.get(vin);
                    
                    if (exteriorTemperature != null && exteriorTemperature > 1) {
                        log.info("{} has high temperature ({}°), skipping defrost because vehicle might be garaged", getVehicleDisplayName(vin), exteriorTemperature);
                        continue;
                    }
                    ZonedDateTime nyTime = Instant.ofEpochSecond(entity.get().getUnixStartTime() / 1000)
                            .atZone(ZoneId.of("America/New_York"));
                    Integer minutesFromMidnight = nyTime.getMinute() + nyTime.getHour() * 60;
                    Integer minutesToDestination;
                    try {
                        // Use adaptive location for defrost calculations as well
                        boolean useCurrentLocation = shouldUseCurrentLocationForScheduling(entity.get().getVin(), event);
                        double startLat = homeLatitude;
                        double startLon = homeLongitude;
                        
                        if (useCurrentLocation) {
                            double[] currentLocation = getCurrentVehicleLocation(entity.get().getVin());
                            startLat = currentLocation[0];
                            startLon = currentLocation[1];
                        }
                        
                        minutesToDestination = Math.toIntExact(routesCalculationService.calculateRouteToDestination(startLat, startLon, event.getLocation())
                                .getRoutesList().stream().max(Comparator.comparing(r -> r.getDuration().getSeconds())).get().getDuration().getSeconds() / 60);
                        if (minutesToDestination > 1000)
                            continue; // do not try and schedule preconditioning for long routes
                    } catch (Exception e) {
                        if (!(event.getLocation().contains("USA") || event.getLocation().contains("US") || event.getLocation().contains("United States") || event.getLocation().contains("United States of America") || event.getLocation().contains("MI") || event.getLocation().contains("Michigan"))) {
                            continue;
                        }
                        log.error("Failed to calculate route to destination for calendar event: {}", event.getId());
                        minutesToDestination = 15; //arbitrary default
                    }
                    Integer desiredPreConditioningTime = minutesFromMidnight - minutesToDestination - preconditioningBufferMinutes;
                    Long minutesUntilDefrost = desiredPreConditioningTime - (System.currentTimeMillis() / (1000 * 60) % (24 * 60));
                    if (minutesUntilDefrost < 0) {
                        minutesUntilDefrost = minutesUntilDefrost + (24 * 60);
                    }
                    if (minutesUntilDefrost > 12 * 60) {
                        continue;
                    }
                    if (vinDefrostMap.get(entity.get().getVin()) == null || vinDefrostMap.get(entity.get().getVin()) < System.currentTimeMillis()) {
                        if (minutesUntilDefrost > 30) {
                            vinDefrostMap.remove(entity.get().getVin());
                            scheduleDefrost(entity.get().getVin(), minutesUntilDefrost);
                        } else {
                            continue;
                        }
                    }
                }
            }
        }
    }

    @Transactional
    private void cleanupDeletedCalendarEvents(List<Event> events) {
        log.info("Cleaning up entities for deleted calendar events");
        List<CalendarPreConditionLinkEntity> entitiesToDelete = new ArrayList<>();
        
        for (CalendarPreConditionLinkEntity entity : calendarPreConditionLinkRepository.findAllByDeleted(false)) {
            // Skip synthetic return home entries - they should be managed by their parent events
            if (entity.getCalendarId().contains("_RETURN_HOME")) {
                String parentEventId = entity.getCalendarId().replace("_RETURN_HOME", "");
                // Only delete if parent event no longer exists
                boolean parentExists = events.stream().anyMatch(e -> e.getId().equals(parentEventId));
                if (!parentExists) {
                    log.info("Parent calendar event {} no longer exists, deleting return home preconditioning entry", parentEventId);
                    entitiesToDelete.add(entity);
                }
            } else if (!events.stream().anyMatch(e -> e.getId().equals(entity.getCalendarId()))) {
                log.info("Calendar event {} no longer exists, deleting preconditioning entry", entity.getCalendarId());
                entitiesToDelete.add(entity);
            }
        }
        
        // Cancel scheduled tasks and mark entities as deleted
        entitiesToDelete.forEach(entity -> {
            cancelExistingTasks(entity.getCalendarId());
            entity.setStatus(PreconditioningStatus.EXPIRED);
            entity.setDeleted(true);
            
            // Send SMS notification about deleted event
            String eventName = entity.getEventSummary() != null ? entity.getEventSummary() : "Unknown Event";
            String vin = entity.getVin();
            boolean isReturnHome = entity.getCalendarId().contains("_RETURN_HOME");
            String eventType = isReturnHome ? "Return home preconditioning" : "Preconditioning";
            
            sendSmsNotification(String.format("%s cancelled for %s - calendar event '%s' was deleted",
                    eventType, getVehicleDisplayName(vin), eventName));
        });
        calendarPreConditionLinkRepository.saveAll(entitiesToDelete);
        
        if (!entitiesToDelete.isEmpty()) {
            log.info("Marked {} entities as deleted and cancelled their scheduled tasks for removed calendar events", entitiesToDelete.size());
        }
    }
}
