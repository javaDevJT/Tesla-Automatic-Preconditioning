package com.jtdev.teslaautomaticpreconditioning.controller;


import com.jtdev.teslaautomaticpreconditioning.entity.Telemetry;
import com.jtdev.teslaautomaticpreconditioning.service.PreConditioningSchedulerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

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
        if (telemetry.getData() != null && telemetry.getData().getLocation() != null) {
            preConditioningSchedulerService.getVinLocationMap().put(vin, telemetry.getData().getLocation());
            // Record telemetry timestamp and location for travel detection
            preConditioningSchedulerService.recordTelemetryTimestamp(vin, currentTime, telemetry.getData().getLocation());
        }
        if (telemetry.getData() != null && telemetry.getData().getInsideTemp() != null) {
            preConditioningSchedulerService.getVinInsideTempMap().put(vin, telemetry.getData().getInsideTemp());
        }
        if (telemetry.getData() != null && telemetry.getData().getOutsideTemp() != null) {
            preConditioningSchedulerService.getVinOutsideTempMap().put(vin, telemetry.getData().getOutsideTemp());
        }
    }
}
