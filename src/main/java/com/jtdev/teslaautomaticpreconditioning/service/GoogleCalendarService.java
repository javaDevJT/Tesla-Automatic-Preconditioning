package com.jtdev.teslaautomaticpreconditioning.service;

import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.Events;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
public class GoogleCalendarService {

    @Autowired
    private Calendar calendar;

    @Value("${google.calendar.name}")
    private String calendarName;

    public Events getCalendar() throws IOException {
        return calendar.events().list(calendarName)
                .setOrderBy("startTime")
                .setSingleEvents(true)
                .setMaxResults(2500)
                .setTimeMin(new DateTime(System.currentTimeMillis()))
                .setTimeMax(new DateTime(System.currentTimeMillis() + 1000L * 60L * 60L * 24L * 2L))
                .execute();
    }
}
