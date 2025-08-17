package com.jtdev.teslaautomaticpreconditioning.controller;

import com.jtdev.teslaautomaticpreconditioning.entity.CalendarPreConditionLinkEntity;
import com.jtdev.teslaautomaticpreconditioning.service.PreConditioningSchedulerService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/scheduler")
public class PreconditioningSchedulerController {

    private final PreConditioningSchedulerService preConditioningSchedulerService;

    public PreconditioningSchedulerController(PreConditioningSchedulerService preConditioningSchedulerService) {
        this.preConditioningSchedulerService = preConditioningSchedulerService;
    }

    @GetMapping("/test")
    public List<CalendarPreConditionLinkEntity> test() throws IOException {
        return preConditioningSchedulerService.runPreConditionScheduleCheck();
    }
}
