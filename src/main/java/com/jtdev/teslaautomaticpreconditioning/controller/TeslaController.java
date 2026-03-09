package com.jtdev.teslaautomaticpreconditioning.controller;


import com.jtdev.teslaautomaticpreconditioning.entity.Telemetry;
import com.jtdev.teslaautomaticpreconditioning.service.PreConditioningSchedulerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/tesla")
public class TeslaController {

    @Autowired
    private PreConditioningSchedulerService preConditioningSchedulerService;

    @PostMapping("/telemetry/{vin}")
    @ResponseStatus(HttpStatus.OK)
    public void saveNewTelemetry(@PathVariable String vin, @RequestBody Telemetry telemetry) {
        log.debug("Saving new telemetry for vin: {}", vin);
        log.debug("Telemetry: {}", telemetry);
        long currentTime = System.currentTimeMillis();
        
        // Check if this telemetry has location data
        boolean hasLocation = telemetry.getData() != null && telemetry.getData().getLocation() != null;
        
        if (hasLocation) {
            preConditioningSchedulerService.getVinLocationMap().put(vin, telemetry.getData().getLocation());
            // Record telemetry timestamp WITH location for travel detection
            preConditioningSchedulerService.recordTelemetryTimestamp(vin, currentTime, telemetry.getData().getLocation());
        } else {
            // Record that telemetry arrived WITHOUT location data
            // This is critical for detecting when a vehicle has parked (location stops but other telemetry continues)
            preConditioningSchedulerService.recordTelemetryWithoutLocation(vin, currentTime);
        }
        
        if (telemetry.getData() != null && telemetry.getData().getInsideTemp() != null) {
            preConditioningSchedulerService.getVinInsideTempMap().put(vin, telemetry.getData().getInsideTemp());
        }
        if (telemetry.getData() != null && telemetry.getData().getOutsideTemp() != null) {
            preConditioningSchedulerService.getVinOutsideTempMap().put(vin, telemetry.getData().getOutsideTemp());
        }
        
        // Extract and store vehicle name from telemetry payload if available
        // Path: other.vehicle_data.vehicle_state.vehicle_name
        extractAndStoreVehicleName(vin, telemetry);
    }
    
    /**
     * Extract vehicle name from telemetry payload and store it
     * The vehicle_name field arrives in: other.vehicle_data.vehicle_state.vehicle_name
     */
    @SuppressWarnings("unchecked")
    private void extractAndStoreVehicleName(String vin, Telemetry telemetry) {
        try {
            if (telemetry.getOther() == null) {
                return;
            }
            
            Object vehicleDataObj = telemetry.getOther().get("vehicle_data");
            if (!(vehicleDataObj instanceof Map)) {
                return;
            }
            
            Map<String, Object> vehicleData = (Map<String, Object>) vehicleDataObj;
            Object vehicleStateObj = vehicleData.get("vehicle_state");
            if (!(vehicleStateObj instanceof Map)) {
                return;
            }
            
            Map<String, Object> vehicleState = (Map<String, Object>) vehicleStateObj;
            Object vehicleNameObj = vehicleState.get("vehicle_name");
            if (vehicleNameObj instanceof String vehicleName && !vehicleName.isBlank()) {
                preConditioningSchedulerService.setVehicleName(vin, vehicleName);
            }
        } catch (Exception e) {
            log.debug("Could not extract vehicle name from telemetry for VIN {}: {}", vin, e.getMessage());
        }
    }
}
