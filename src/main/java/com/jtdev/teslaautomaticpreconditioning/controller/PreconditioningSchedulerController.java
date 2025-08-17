package com.jtdev.teslaautomaticpreconditioning.controller;

import com.jtdev.teslaautomaticpreconditioning.entity.CalendarPreConditionLinkEntity;
import com.jtdev.teslaautomaticpreconditioning.service.PreConditioningSchedulerService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import com.jtdev.teslaautomaticpreconditioning.fleetapi.VehicleData;


@RestController
@RequestMapping("/scheduler")
public class PreconditioningSchedulerController {

    private final PreConditioningSchedulerService preConditioningSchedulerService;

    public PreconditioningSchedulerController(PreConditioningSchedulerService preConditioningSchedulerService) {
        this.preConditioningSchedulerService = preConditioningSchedulerService;
    }

    @GetMapping("/test")
    public List<CalendarPreConditionLinkEntity> test() throws IOException, InterruptedException {
        return preConditioningSchedulerService.runPreConditionScheduleCheck();
    }

    @GetMapping
    public List<CalendarPreConditionLinkEntity> calendarPreConditionLinkEntities() {
        return  preConditioningSchedulerService.getCurrentEntries();
    }

    /**
     * Smart cleanup of irrelevant preconditioning schedules from a specific vehicle
     * @param vin Vehicle identification number
     * @return List of preconditioning schedules that could not be deleted
     */
    @PostMapping("/smart-cleanup/{vin}")
    public List<VehicleData.PreconditionSchedule> smartCleanupPreconditioningSchedules(@PathVariable String vin) throws InterruptedException, IOException {
        // Get current calendar events and managed entities for context
        List<CalendarPreConditionLinkEntity> managedEntities = preConditioningSchedulerService.getCurrentEntries().stream()
                .filter(entity -> vin.equals(entity.getVin()))
                .toList();
        // For API call, we'll pass empty events list since this is manual cleanup
        return preConditioningSchedulerService.smartCleanupPreconditioningSchedules(vin, managedEntities, java.util.Collections.emptyList());
    }
}
