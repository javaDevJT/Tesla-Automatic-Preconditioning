package com.jtdev.teslaautomaticpreconditioning.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.jtdev.teslaautomaticpreconditioning.entity.CalendarPreConditionLinkEntity;
import com.jtdev.teslaautomaticpreconditioning.service.FleetApiService;
import com.jtdev.teslaautomaticpreconditioning.repository.CalendarPreConditionLinkRepository;
import com.jtdev.teslaautomaticpreconditioning.service.GoogleCalendarService;
import com.jtdev.teslaautomaticpreconditioning.service.RoutesCalculationService;
import com.jtdev.teslaautomaticpreconditioning.service.WeatherService;
import com.jtdev.teslaautomaticpreconditioning.service.VehicleApiUsageTracker;
import com.jtdev.teslaautomaticpreconditioning.entity.PreconditioningStatus;
import com.jtdev.teslaautomaticpreconditioning.entity.Telemetry;
import com.jtdev.teslaautomaticpreconditioning.fleetapi.VehicleData;
import com.jtdev.teslaautomaticpreconditioning.fleetapi.RemovePreconditionSchedule;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import com.google.maps.routing.v2.ComputeRoutesResponse;
import java.io.IOException;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class PreConditioningSchedulerServiceTest {

    @Mock
    private FleetApiService fleetApiService;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private GoogleCalendarService googleCalendarService;

    @Mock
    private CalendarPreConditionLinkRepository calendarPreConditionLinkRepository;

    @Mock
    private RoutesCalculationService routesCalculationService;

    @Mock
    private WeatherService weatherService;

    @Mock
    private VehicleApiUsageTracker apiUsageTracker;

    @Mock
    private IntelligentVehicleDataCache vehicleDataCache;

    @Mock
    private SmartScheduleManager smartScheduleManager;

    @InjectMocks
    private PreConditioningSchedulerService schedulingService;

    private final String VIN = "1HGCM82633A123456";
    private final String CALENDAR_ID = "event123";

    @BeforeEach
    public void setUp() {
        // Mock the current location for vehicle - use proper constructor
        Telemetry.Location location = new Telemetry.Location();
        location.setLatitude(37.7749);
        location.setLongitude(-122.4194);
        schedulingService.getVinLocationMap().put(VIN, location);
    }



    @Test
    public void testIsVehicleTraveling_WithFrequentUpdatesAndMovement_ShouldReturnTrue() {
        // Arrange - simulate frequent telemetry updates with movement (every 30 seconds for 5 minutes)
        long baseTime = System.currentTimeMillis();
        for (int i = 0; i < 10; i++) {
            // Simulate movement of about 0.5km every 30 seconds (60 km/h)
            Telemetry.Location location = new Telemetry.Location();
            location.setLatitude(37.7749 + (i * 0.005)); // Moving north
            location.setLongitude(-122.4194 + (i * 0.005)); // Moving east
            schedulingService.recordTelemetryTimestamp(VIN, baseTime + (i * 30 * 1000L), location);
        }

        // Act
        boolean result = schedulingService.isVehicleTraveling(VIN);

        // Assert
        assertTrue(result, "Vehicle should be considered traveling with frequent updates and movement");
    }

    @Test
    public void testIsVehicleTraveling_WithFrequentUpdatesButNoMovement_ShouldReturnFalse() {
        // Arrange - simulate frequent telemetry updates but minimal movement (GPS noise)
        long baseTime = System.currentTimeMillis();
        for (int i = 0; i < 10; i++) {
            // Simulate GPS noise - less than 10 meters movement
            Telemetry.Location location = new Telemetry.Location();
            location.setLatitude(37.7749 + (i * 0.00005)); // Tiny movement (about 5-10 meters)
            location.setLongitude(-122.4194 + (i * 0.00005));
            schedulingService.recordTelemetryTimestamp(VIN, baseTime + (i * 30 * 1000L), location);
        }

        // Act
        boolean result = schedulingService.isVehicleTraveling(VIN);

        // Assert
        assertFalse(result, "Vehicle should not be considered traveling with frequent updates but no significant movement");
    }

    @Test
    public void testIsVehicleTraveling_WithInfrequentUpdates_ShouldReturnFalse() {
        // Arrange - simulate infrequent telemetry updates (every 5 minutes for 30 minutes)
        long baseTime = System.currentTimeMillis();
        for (int i = 0; i < 6; i++) {
            schedulingService.recordTelemetryTimestamp(VIN, baseTime + (i * 5 * 60 * 1000L), null);
        }

        // Act
        boolean result = schedulingService.isVehicleTraveling(VIN);

        // Assert
        assertFalse(result, "Vehicle should not be considered traveling with infrequent updates");
    }

    @Test
    public void testIsVehicleTraveling_WithInsufficientData_ShouldReturnFalse() {
        // Arrange - only one telemetry timestamp
        schedulingService.recordTelemetryTimestamp(VIN, System.currentTimeMillis(), null);

        // Act
        boolean result = schedulingService.isVehicleTraveling(VIN);

        // Assert
        assertFalse(result, "Vehicle should not be considered traveling with insufficient data");
    }

    @Test
    public void testRecordTelemetryTimestamp_TransitionFromTravelingToStationary_ShouldUpdateState() {
        // Arrange - set up previous state as traveling
        schedulingService.getVinPreviousTravelState().put(VIN, true);

        // Simulate infrequent updates (stationary)
        long baseTime = System.currentTimeMillis();
        for (int i = 0; i < 3; i++) {
            schedulingService.recordTelemetryTimestamp(VIN, baseTime + (i * 10 * 60 * 1000L), null); // Every 10 minutes
        }

        // Act - record one more timestamp
        schedulingService.recordTelemetryTimestamp(VIN, baseTime + (4 * 10 * 60 * 1000L), null);

        // Assert - verify that the transition was detected
        assertFalse(schedulingService.getVinPreviousTravelState().get(VIN),
            "Previous travel state should be updated to stationary");
    }



}
