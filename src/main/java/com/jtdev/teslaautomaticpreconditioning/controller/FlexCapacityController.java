package com.jtdev.teslaautomaticpreconditioning.controller;

import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.Events;
import com.jtdev.teslaautomaticpreconditioning.service.GoogleCalendarService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
public class FlexCapacityController {

    @Autowired
    private GoogleCalendarService googleCalendarService;

    private static final double MAX_WEEKLY_HOURS = 40.0;
    private static final ZoneId TIMEZONE = ZoneId.of("America/New_York");

    @GetMapping(value = "/flex", produces = MediaType.TEXT_HTML_VALUE)
    public String getFlexCapacity() throws IOException {
        log.info("Flex capacity report requested");

        // Get today at midnight
        LocalDate today = LocalDate.now(TIMEZONE);
        LocalDate sevenDaysAgo = today.minusDays(7);
        LocalDate sevenDaysFromNow = today.plusDays(7);

        // Initial fetch to find earliest relevant flex event
        ZonedDateTime initialRangeStart = sevenDaysAgo.atStartOfDay(TIMEZONE);
        ZonedDateTime initialRangeEnd = sevenDaysFromNow.atTime(23, 59, 59).atZone(TIMEZONE);

        Events initialEvents = googleCalendarService.getEventsInDateRange(
                initialRangeStart.toInstant().toEpochMilli(),
                initialRangeEnd.toInstant().toEpochMilli()
        );

        // Filter for flex events (case-insensitive)
        List<Event> initialFlexEvents = initialEvents.getItems().stream()
                .filter(e -> e.getSummary() != null && 
                           e.getSummary().toLowerCase().contains("flex") &&
                           e.getStart() != null && e.getStart().getDateTime() != null)
                .collect(Collectors.toList());

        // Find earliest flex event to determine display start date
        LocalDate displayStart = today;
        if (!initialFlexEvents.isEmpty()) {
            LocalDate earliestFlexDate = initialFlexEvents.stream()
                    .map(e -> Instant.ofEpochMilli(e.getStart().getDateTime().getValue())
                            .atZone(TIMEZONE)
                            .toLocalDate())
                    .min(LocalDate::compareTo)
                    .orElse(today);
            
            // Only show past dates if there are relevant flex events
            if (earliestFlexDate.isBefore(today) && earliestFlexDate.isAfter(sevenDaysAgo.minusDays(1))) {
                displayStart = earliestFlexDate;
            }
        }

        LocalDate displayEnd = sevenDaysFromNow;

        // Now fetch events with extended range to cover trailing 7-day calculations
        // For accurate trailing 7-day totals, we need data from 6 days before displayStart
        LocalDate dataFetchStart = displayStart.minusDays(6);
        ZonedDateTime extendedRangeStart = dataFetchStart.atStartOfDay(TIMEZONE);
        
        Events allEvents = googleCalendarService.getEventsInDateRange(
                extendedRangeStart.toInstant().toEpochMilli(),
                initialRangeEnd.toInstant().toEpochMilli()
        );

        // Filter for flex events from extended range
        List<Event> flexEvents = allEvents.getItems().stream()
                .filter(e -> e.getSummary() != null && 
                           e.getSummary().toLowerCase().contains("flex") &&
                           e.getStart() != null && e.getStart().getDateTime() != null)
                .collect(Collectors.toList());

        // Build day-by-day data
        Map<LocalDate, DayData> dayDataMap = new LinkedHashMap<>();
        for (LocalDate date = displayStart; !date.isAfter(displayEnd); date = date.plusDays(1)) {
            dayDataMap.put(date, calculateDayData(date, flexEvents));
        }

        // Second pass: adjust available capacity based on forward-looking constraints
        // Adding hours to day N affects trailing totals for days N through N+6
        for (LocalDate date = displayStart; !date.isAfter(displayEnd); date = date.plusDays(1)) {
            DayData currentDay = dayDataMap.get(date);
            double constrainedCapacity = currentDay.availableCapacity;
            
            // Check next 6 days - adding hours to current day will affect their trailing totals
            for (int i = 1; i <= 6; i++) {
                LocalDate futureDate = date.plusDays(i);
                DayData futureDay = dayDataMap.get(futureDate);
                if (futureDay != null) {
                    // If we add X hours to current day, future day's trailing total increases by X
                    // So max we can add is: 40 - futureDay.trailing7DayHours
                    constrainedCapacity = Math.min(constrainedCapacity, 
                                                   MAX_WEEKLY_HOURS - futureDay.trailing7DayHours);
                }
            }
            
            currentDay.availableCapacity = Math.max(0, constrainedCapacity);
            
            // Update status color based on constrained capacity
            if (currentDay.availableCapacity >= 4.1) {
                currentDay.statusColor = "green";
                currentDay.statusEmoji = "🟢";
            } else if (currentDay.availableCapacity >= 1.0) {
                currentDay.statusColor = "yellow";
                currentDay.statusEmoji = "🟡";
            } else {
                currentDay.statusColor = "red";
                currentDay.statusEmoji = "🔴";
            }
        }

        // Generate HTML
        return generateHtml(dayDataMap, today);
    }

    private DayData calculateDayData(LocalDate date, List<Event> allFlexEvents) {
        DayData dayData = new DayData();
        dayData.date = date;

        // Get flex events on this specific day
        dayData.eventsThisDay = allFlexEvents.stream()
                .filter(e -> {
                    LocalDate eventDate = Instant.ofEpochMilli(e.getStart().getDateTime().getValue())
                            .atZone(TIMEZONE)
                            .toLocalDate();
                    return eventDate.equals(date);
                })
                .collect(Collectors.toList());

        // Calculate hours for events on this day
        dayData.hoursThisDay = dayData.eventsThisDay.stream()
                .mapToDouble(this::calculateEventHours)
                .sum();

        // Calculate trailing 7-day total (this day + previous 6 days)
        LocalDate sevenDaysAgo = date.minusDays(6);
        dayData.trailing7DayHours = allFlexEvents.stream()
                .filter(e -> {
                    LocalDate eventDate = Instant.ofEpochMilli(e.getStart().getDateTime().getValue())
                            .atZone(TIMEZONE)
                            .toLocalDate();
                    return !eventDate.isBefore(sevenDaysAgo) && !eventDate.isAfter(date);
                })
                .mapToDouble(this::calculateEventHours)
                .sum();

        // Calculate available capacity
        dayData.availableCapacity = Math.max(0, MAX_WEEKLY_HOURS - dayData.trailing7DayHours);

        // Determine status color
        if (dayData.availableCapacity >= 4.1) {
            dayData.statusColor = "green";
            dayData.statusEmoji = "🟢";
        } else if (dayData.availableCapacity >= 1.0) {
            dayData.statusColor = "yellow";
            dayData.statusEmoji = "🟡";
        } else {
            dayData.statusColor = "red";
            dayData.statusEmoji = "🔴";
        }

        return dayData;
    }

    private double calculateEventHours(Event event) {
        if (event.getStart() == null || event.getStart().getDateTime() == null ||
            event.getEnd() == null || event.getEnd().getDateTime() == null) {
            return 0.0;
        }

        long startMillis = event.getStart().getDateTime().getValue();
        long endMillis = event.getEnd().getDateTime().getValue();
        long durationMillis = endMillis - startMillis;

        return durationMillis / (1000.0 * 60.0 * 60.0); // Convert to hours
    }

    private String generateHtml(Map<LocalDate, DayData> dayDataMap, LocalDate today) {
        StringBuilder html = new StringBuilder();
        
        html.append("<!DOCTYPE html>\n");
        html.append("<html>\n<head>\n");
        html.append("<meta charset='UTF-8'>\n");
        html.append("<meta name='viewport' content='width=device-width, initial-scale=1.0'>\n");
        html.append("<title>Flex Schedule Capacity</title>\n");
        html.append("<style>\n");
        html.append("body { font-family: Arial, sans-serif; margin: 20px; background: #f5f5f5; }\n");
        html.append("h1 { text-align: center; color: #333; }\n");
        html.append(".info { text-align: center; color: #666; margin-bottom: 20px; }\n");
        html.append(".calendar { display: flex; flex-wrap: wrap; gap: 10px; justify-content: center; }\n");
        html.append(".day-cell { border: 2px solid #ddd; border-radius: 8px; padding: 12px; min-width: 150px; max-width: 180px; background: white; }\n");
        html.append(".day-cell.today { border-color: #0066cc; border-width: 3px; }\n");
        html.append(".day-cell.past { opacity: 0.7; }\n");
        html.append(".day-cell.green { background: #e8f5e9; }\n");
        html.append(".day-cell.yellow { background: #fff9c4; }\n");
        html.append(".day-cell.red { background: #ffebee; }\n");
        html.append(".day-header { font-weight: bold; font-size: 16px; margin-bottom: 8px; text-align: center; }\n");
        html.append(".day-name { font-size: 12px; color: #666; text-align: center; }\n");
        html.append(".events { margin: 8px 0; font-size: 12px; color: #333; min-height: 40px; }\n");
        html.append(".event-item { margin: 4px 0; padding: 4px; background: #f0f0f0; border-radius: 4px; }\n");
        html.append(".metrics { margin-top: 8px; padding-top: 8px; border-top: 1px solid #ddd; font-size: 13px; }\n");
        html.append(".metric-row { margin: 4px 0; }\n");
        html.append(".metric-label { color: #666; }\n");
        html.append(".metric-value { font-weight: bold; }\n");
        html.append(".status { text-align: center; font-size: 20px; margin: 8px 0; }\n");
        html.append(".legend { text-align: center; margin-top: 20px; padding: 15px; background: white; border-radius: 8px; }\n");
        html.append(".legend-item { display: inline-block; margin: 0 15px; }\n");
        html.append("</style>\n");
        html.append("</head>\n<body>\n");
        
        html.append("<h1>Flex Schedule Capacity Planner</h1>\n");
        html.append("<div class='info'>40-hour weekly limit • Trailing 7-day calculation</div>\n");
        
        html.append("<div class='calendar'>\n");
        
        for (Map.Entry<LocalDate, DayData> entry : dayDataMap.entrySet()) {
            LocalDate date = entry.getKey();
            DayData data = entry.getValue();
            
            String cellClass = "day-cell " + data.statusColor;
            if (date.equals(today)) {
                cellClass += " today";
            } else if (date.isBefore(today)) {
                cellClass += " past";
            }
            
            html.append("<div class='").append(cellClass).append("'>\n");
            
            // Date header
            html.append("<div class='day-header'>").append(date.format(DateTimeFormatter.ofPattern("MMM d"))).append("</div>\n");
            html.append("<div class='day-name'>").append(date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.US)).append("</div>\n");
            
            // Events on this day
            html.append("<div class='events'>\n");
            if (!data.eventsThisDay.isEmpty()) {
                for (Event event : data.eventsThisDay) {
                    ZonedDateTime eventStart = Instant.ofEpochMilli(event.getStart().getDateTime().getValue())
                            .atZone(TIMEZONE);
                    String timeStr = eventStart.format(DateTimeFormatter.ofPattern("h:mm a"));
                    double hours = calculateEventHours(event);
                    html.append("<div class='event-item'>")
                        .append(event.getSummary())
                        .append("<br><small>").append(timeStr)
                        .append(" (").append(String.format("%.1f", hours)).append("h)</small>")
                        .append("</div>\n");
                }
            } else {
                html.append("<div style='color: #999; text-align: center;'>No events</div>\n");
            }
            html.append("</div>\n");
            
            // Metrics
            html.append("<div class='metrics'>\n");
            html.append("<div class='metric-row'><span class='metric-label'>Day total:</span> <span class='metric-value'>")
                .append(String.format("%.1f", data.hoursThisDay)).append("h</span></div>\n");
            html.append("<div class='metric-row'><span class='metric-label'>7-day total:</span> <span class='metric-value'>")
                .append(String.format("%.1f", data.trailing7DayHours)).append("h</span></div>\n");
            html.append("<div class='metric-row'><span class='metric-label'>Available:</span> <span class='metric-value'>")
                .append(String.format("%.1f", data.availableCapacity)).append("h</span></div>\n");
            html.append("</div>\n");
            
            // Status indicator
            html.append("<div class='status'>").append(data.statusEmoji).append("</div>\n");
            
            html.append("</div>\n");
        }
        
        html.append("</div>\n");
        
        // Legend
        html.append("<div class='legend'>\n");
        html.append("<div class='legend-item'>🟢 <strong>Green:</strong> 4+ hours available</div>\n");
        html.append("<div class='legend-item'>🟡 <strong>Yellow:</strong> 1-4 hours available</div>\n");
        html.append("<div class='legend-item'>🔴 <strong>Red:</strong> At/over limit</div>\n");
        html.append("</div>\n");
        
        html.append("</body>\n</html>");
        
        return html.toString();
    }

    private static class DayData {
        LocalDate date;
        List<Event> eventsThisDay;
        double hoursThisDay;
        double trailing7DayHours;
        double availableCapacity;
        String statusColor;
        String statusEmoji;
    }
}
