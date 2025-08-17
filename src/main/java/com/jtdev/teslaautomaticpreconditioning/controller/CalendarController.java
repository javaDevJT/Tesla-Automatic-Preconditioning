package com.jtdev.teslaautomaticpreconditioning.controller;

import com.google.api.services.calendar.model.Events;
import com.jtdev.teslaautomaticpreconditioning.service.GoogleCalendarService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequestMapping("/calendar")
public class CalendarController {

    private final GoogleCalendarService googleCalendarService;

    public CalendarController(GoogleCalendarService googleCalendarService) {
        this.googleCalendarService = googleCalendarService;
    }

    @GetMapping
    public Events getCalendar() throws IOException {
        return googleCalendarService.getCalendar();
    }
}
