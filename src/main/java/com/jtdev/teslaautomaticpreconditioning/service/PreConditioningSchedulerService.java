package com.jtdev.teslaautomaticpreconditioning.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventAttendee;
import com.jtdev.teslaautomaticpreconditioning.entity.CalendarPreConditionLinkEntity;
import com.jtdev.teslaautomaticpreconditioning.fleetapi.AddPreconditioningScheduleBody;
import com.jtdev.teslaautomaticpreconditioning.fleetapi.ModifyPreconditioningScheduleBody;
import com.jtdev.teslaautomaticpreconditioning.fleetapi.RemovePreconditionSchedule;
import com.jtdev.teslaautomaticpreconditioning.fleetapi.VehicleData;
import com.jtdev.teslaautomaticpreconditioning.repository.CalendarPreConditionLinkRepository;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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

    @Value("${tesla.preconditioning.completion.delay.minutes:10}")
    private int completionDelayMinutes;

    private Map<String, Long> vinDefrostMap = new HashMap<>();

    // Lightweight scheduler for delayed tasks
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @Transactional
    @Scheduled(cron = "0 30 6-22 * * *")
    public List<CalendarPreConditionLinkEntity> runPreConditionScheduleCheck() throws IOException {
        log.debug("Running precondition schedule check");
        List<Event> events = googleCalendarService.getCalendar().getItems().stream().filter(e -> e.getStart().getDateTime() != null).toList();
        List<String> emailsAndVins = Arrays.stream(vinCsv.split(",")).toList();
        Map<String, String> vinToEmailMap = emailsAndVins.stream()
                .map(s -> s.split(":")).collect(java.util.stream.Collectors.toMap(s -> s[1],
                        s -> s[0]));
        Map<String, List<VehicleData.PreconditionSchedule>> vinPreconditionSchedulesMap = new HashMap<>();
        for (String vin : vinToEmailMap.keySet()) {
           ArrayList<VehicleData.PreconditionSchedule> hashPreconditionSchedulesMap =
                    fleetApiService.getVehicleData(vin).getPreconditioning_schedule_data().getPrecondition_schedules();
            vinPreconditionSchedulesMap.put(vin, hashPreconditionSchedulesMap);
        }
        List<Event> addEventList = new ArrayList<>();
        for (Event event : events) {
            if (conformToCalendarEventReturnFalseToSkipAdd(event, vinPreconditionSchedulesMap, vinToEmailMap)) {
                addEventList.add(event);
            }
        }
        addMissingPreconditioningEntries(addEventList, vinToEmailMap);
        deleteRemovedCalendarEvents(events);
        scheduleWeatherCheck();
        return calendarPreConditionLinkRepository.findAll();
    }

    @Transactional
    public void addMissingPreconditioningEntries(List<Event> events, Map<String, String> vinToEmailMap) throws JsonProcessingException {
        log.debug("Running addMissingPreconditioningEntries");
        for (Event event : events) {
            if (event.getLocation() == null) continue;
            if (!calendarPreConditionLinkRepository.existsByCalendarId(event.getId())) {
                CalendarPreConditionLinkEntity calendarPreConditionLinkEntity = new CalendarPreConditionLinkEntity();
                calendarPreConditionLinkEntity.setCalendarId(event.getId());
                calendarPreConditionLinkEntity.setUnixStartTime(event.getStart().getDateTime().getValue());
                String emailToAssign = null;
                if (event.getAttendees() != null) {
                    List<EventAttendee> validAttendees = event.getAttendees().stream().filter(e ->
                            vinToEmailMap.entrySet().stream()
                                    .anyMatch(e2 -> e2.getValue().equals(e.getEmail()))).toList();
                    if (validAttendees.size() == 1) {
                        emailToAssign = validAttendees.get(0).getEmail();
                    }
                }
                if (emailToAssign == null) {
                    boolean isCreatorValid = vinToEmailMap.entrySet().stream()
                            .anyMatch(e2 -> e2.getValue().equals(event.getCreator().getEmail()));
                    if (isCreatorValid) {
                        emailToAssign = event.getCreator().getEmail();
                    } else {
                        log.debug("No valid attendees found for calendar event: {}", event.getId());
                        continue;
                    }
                }
                calendarPreConditionLinkEntity.setAttendeeEmail(emailToAssign);
                String finalEmailToAssign = emailToAssign;
                String vin = vinToEmailMap.entrySet().stream()
                        .filter(e2 -> e2.getValue().equals(finalEmailToAssign)).findFirst().get().getKey();
                AddPreconditioningScheduleBody addPreconditioningScheduleBody = new AddPreconditioningScheduleBody();
                addPreconditioningScheduleBody.setLat(homeLatitude);
                addPreconditioningScheduleBody.setLon(homeLongitude);
                addPreconditioningScheduleBody.setEnabled(true);
                addPreconditioningScheduleBody.setOne_time(true);
                addPreconditioningScheduleBody.setWait_for_completion(true);
                ZonedDateTime nyTime = Instant.ofEpochSecond(calendarPreConditionLinkEntity.getUnixStartTime() / 1000)
                        .atZone(ZoneId.of("America/New_York"));
                Integer minutesFromMidnight = nyTime.getMinute() + nyTime.getHour() * 60;
                Integer minutesToDestination;
                try {
                    minutesToDestination = Math.toIntExact(routesCalculationService.calculateRouteToDestination(homeLatitude, homeLongitude, event.getLocation())
                            .getRoutesList().stream().max(Comparator.comparing(r -> r.getDuration().getSeconds())).get().getDuration().getSeconds() / 60);
                    if (minutesToDestination > 1000) continue; // do not try and schedule preconditioning for long routes
                } catch (Exception e) {
                    if (!(event.getLocation().contains("USA") || event.getLocation().contains("US") || event.getLocation().contains("United States") || event.getLocation().contains("United States of America") || event.getLocation().contains("MI") || event.getLocation().contains("Michigan"))) {
                        continue;
                    }
                    log.error("Failed to calculate route to destination for calendar event: {}", event.getId());
                    minutesToDestination = 15; //arbitrary default
                }
                Integer desiredPreConditioningTime = minutesFromMidnight - minutesToDestination - preconditioningBufferMinutes;
                DayOfWeek dayOfWeek = nyTime.getDayOfWeek();
                addPreconditioningScheduleBody.setPrecondition_time(desiredPreConditioningTime);
                addPreconditioningScheduleBody.setDays_of_week(dayOfWeek.name().toUpperCase().charAt(0) +
                        dayOfWeek.name().substring(1).toLowerCase());
                boolean added = fleetApiService.addPreconditioningEntry(vin, addPreconditioningScheduleBody);
                if (added) {
                    // Defer completion check to allow Tesla backend to surface the schedule
                    scheduleCheckCompletion(event, calendarPreConditionLinkEntity, vin, desiredPreConditioningTime, dayOfWeek);
                } else {
                    log.debug("Failed to add preconditioning entry for calendar event: {}", event.getId());
                }
            }
        }
    }

    private void checkCompletionInternal(Event event, CalendarPreConditionLinkEntity calendarPreConditionLinkEntity, String vin, Integer desiredPreConditioningTime, DayOfWeek dayOfWeek) throws JsonProcessingException {
        calendarPreConditionLinkEntity.setVin(vin);
        Optional<VehicleData.PreconditionSchedule> completedSchedule = fleetApiService.getVehicleDataNoCache(vin)
                .getPreconditioning_schedule_data().getPrecondition_schedules()
                .stream()
                .filter(VehicleData.PreconditionSchedule::isEnabled)
                .filter(VehicleData.PreconditionSchedule::isOne_time)
                .filter(schedule -> schedule.getPrecondition_time() == desiredPreConditioningTime)
                .filter(schedule -> fleetApiService.decodeDaysOfWeek(schedule.getDays_of_week()).contains(dayOfWeek))
                .findFirst();
        if (!completedSchedule.isPresent()) {
            log.debug("Failed to add preconditioning entry for calendar event: {}", event.getId());
            return;
        }
        calendarPreConditionLinkEntity.setPreconditionId(completedSchedule.get().getId());
        calendarPreConditionLinkRepository.save(calendarPreConditionLinkEntity);
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
                    checkCompletionInternal(event, calendarPreConditionLinkEntity, vin, desiredPreConditioningTime, dayOfWeek);
                } catch (JsonProcessingException e) {
                    log.error("Failed during delayed checkCompletion for calendar event: {}", event.getId(), e);
                }
            });
        }, completionDelayMinutes, TimeUnit.MINUTES);
    }

    public void scheduleWeatherCheck() {
        scheduler.schedule(() -> {
            TransactionTemplate tt = new TransactionTemplate(transactionManager);
            // Use a new transaction so this runs independently of the caller
            tt.executeWithoutResult(status -> {
                try {
                    weatherCheckInternal();
                } catch (Exception e) {
                }
            });
        }, completionDelayMinutes + 5L, TimeUnit.MINUTES);
    }

    public void weatherCheckInternal() throws IOException {
        List<CalendarPreConditionLinkEntity> entities = calendarPreConditionLinkRepository.findAll();
        entities = entities.stream().filter(e ->
                System.currentTimeMillis() - e.getUnixStartTime() < (1000 * 60 * 60 * 2)).toList();
        if (entities.isEmpty()) {
            return;
        } else {
            log.debug("Running weather check for {} calendar entries", entities.size());
            boolean highRisk = weatherService.hasHighSnowOrIceCoverageLast12h(homeLatitude, homeLongitude, Optional.empty());
            if (highRisk) {
                log.debug("High risk of snow accumulation or ice cover, scheduling a defrost for {} entries", entities.size());
                List<CalendarPreConditionLinkEntity> finalEntities = entities;
                List<Event> events = googleCalendarService.getCalendar().getItems().stream().filter(c -> finalEntities.stream().map(x -> x.getCalendarId()).toList().contains(c.getId())).toList();
                for (Event event : events) {
                    if (event.getLocation() == null) continue;
                    Optional<CalendarPreConditionLinkEntity> entity = calendarPreConditionLinkRepository.findByCalendarId(event.getId());
                    if (!entity.isPresent()) {
                        continue;
                    }
                    ZonedDateTime nyTime = Instant.ofEpochSecond(entity.get().getUnixStartTime() / 1000)
                            .atZone(ZoneId.of("America/New_York"));
                    Integer minutesFromMidnight = nyTime.getMinute() + nyTime.getHour() * 60;
                    Integer minutesToDestination;
                    try {
                        minutesToDestination = Math.toIntExact(routesCalculationService.calculateRouteToDestination(homeLatitude, homeLongitude, event.getLocation())
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
                    Integer desiredPreConditioningTime = minutesFromMidnight - minutesToDestination - 30; //30-minute defrost time
                    ZonedDateTime nowNyTime = Instant.ofEpochSecond(System.currentTimeMillis() / 1000)
                            .atZone(ZoneId.of("America/New_York"));
                    Integer minutesFromMidnightNow = nowNyTime.getMinute() + nowNyTime.getHour() * 60;
                    Integer minutesUntilDefrost = desiredPreConditioningTime - minutesFromMidnightNow;
                    if (vinDefrostMap.containsKey(entity.get().getVin())) {
                        if (vinDefrostMap.get(entity.get().getVin()) + 1000 * 60 * 60 * 2 < System.currentTimeMillis()) {
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

    public void scheduleDefrost(String vin, Integer minutesUntilDefrost) {
        log.debug("Scheduling defrost for vin: {} in {} minutes", vin, minutesUntilDefrost);
        vinDefrostMap.put(vin, System.currentTimeMillis() + (1000L * 60 * minutesUntilDefrost));
        scheduler.schedule(() -> {
            if (fleetApiService.startMaxDefrost(vin)) {
                log.debug("Successfully started defrost for vin: {}", vin);
            } else {
                log.debug("Failed to start defrost for vin: {}", vin);
            }
            vinDefrostMap.remove(vin);
        }, minutesUntilDefrost, TimeUnit.MINUTES);
    }

    @Transactional
    public void deleteRemovedCalendarEvents(List<Event> events) throws JsonProcessingException {
        List<CalendarPreConditionLinkEntity> deleteList = new ArrayList<>();
        for (CalendarPreConditionLinkEntity entity : calendarPreConditionLinkRepository.findAll()) {
            if (!events.stream().anyMatch(e -> e.getId().equals(entity.getCalendarId()))) {
                RemovePreconditionSchedule removePreconditionSchedule = new RemovePreconditionSchedule();
                removePreconditionSchedule.setId(entity.getPreconditionId());
                removePreconditionSchedule.setWait_for_completion(true);
                boolean deleted = fleetApiService.deletePreconditioningEntry(entity.getVin(), removePreconditionSchedule);
                if (deleted) deleteList.add(entity);
            }
        }
        calendarPreConditionLinkRepository.deleteAll(deleteList);
    }

    private boolean conformToCalendarEventReturnFalseToSkipAdd(Event event,
                                                               Map<String, List<VehicleData.PreconditionSchedule>>
                                                                       vinPreconditionSchedulesMap,
                                                               Map<String, String> vinToEmailMap)
            throws JsonProcessingException {
        Optional<CalendarPreConditionLinkEntity> entity = calendarPreConditionLinkRepository.findByCalendarId(event.getId());
        if (!entity.isPresent()) {
            return true;
        }
        if (entity.get().getUnixStartTime() != event.getStart().getDateTime().getValue()) {
            log.debug("Calendar event start time changed, rescheduling preconditioning for calendar event: {}", event.getId());
            RemovePreconditionSchedule removePreconditionSchedule = new RemovePreconditionSchedule();
            removePreconditionSchedule.setId(entity.get().getPreconditionId());
            removePreconditionSchedule.setWait_for_completion(true);
            boolean deleted = fleetApiService.deletePreconditioningEntry(entity.get().getVin(), removePreconditionSchedule);
            if (deleted) calendarPreConditionLinkRepository.delete(entity.get());
            return true;
        }
        String vinOfEntity = entity.get().getVin();
        List<VehicleData.PreconditionSchedule> vinPreconditionSchedules = vinPreconditionSchedulesMap.get(vinOfEntity);
        if (vinPreconditionSchedules.stream().noneMatch(v -> v.getId() == entity.get().getPreconditionId())) {
            log.debug("Something odd has happened, logging for informational purposes only. Calendar event: {}, vin: {}", event.getId(), vinOfEntity);
        }
        ZonedDateTime nyTime = Instant.ofEpochSecond(entity.get().getUnixStartTime() / 1000)
                .atZone(ZoneId.of("America/New_York"));
        Integer minutesFromMidnight = nyTime.getMinute() + nyTime.getHour() * 60;
        Integer minutesToDestination;
        try {
            minutesToDestination = Math.toIntExact(routesCalculationService.calculateRouteToDestination(homeLatitude, homeLongitude, event.getLocation())
                    .getRoutesList().stream().max(Comparator.comparing(r -> r.getDuration().getSeconds())).get().getDuration().getSeconds() / 60);
            if (minutesToDestination > 1000) {
                RemovePreconditionSchedule removePreconditionSchedule = new RemovePreconditionSchedule();
                removePreconditionSchedule.setId(entity.get().getPreconditionId());
                removePreconditionSchedule.setWait_for_completion(true);
                boolean deleted = fleetApiService.deletePreconditioningEntry(vinOfEntity, removePreconditionSchedule);
                if (deleted) calendarPreConditionLinkRepository.delete(entity.get());
                return false;
            }
        } catch (Exception e) {
            if (!(event.getLocation().contains("USA") || event.getLocation().contains("US") || event.getLocation().contains("United States") || event.getLocation().contains("United States of America") || event.getLocation().contains("MI") || event.getLocation().contains("Michigan"))) {
                RemovePreconditionSchedule removePreconditionSchedule = new RemovePreconditionSchedule();
                removePreconditionSchedule.setId(entity.get().getPreconditionId());
                removePreconditionSchedule.setWait_for_completion(true);
                boolean deleted = fleetApiService.deletePreconditioningEntry(vinOfEntity, removePreconditionSchedule);
                if (deleted) calendarPreConditionLinkRepository.delete(entity.get());
                return false;
            }
            log.error("Failed to calculate route to destination for calendar event: {}", event.getId());
            minutesToDestination = 15; //arbitrary default
        }
        Integer desiredPreConditioningTime = minutesFromMidnight - minutesToDestination - preconditioningBufferMinutes;
        DayOfWeek dayOfWeek = nyTime.getDayOfWeek();
        Optional<VehicleData.PreconditionSchedule> currentSchedule = vinPreconditionSchedules.stream()
                .filter(v -> v.getId() == entity.get().getPreconditionId())
                .filter(VehicleData.PreconditionSchedule::isEnabled)
                .filter(VehicleData.PreconditionSchedule::isOne_time)
                .filter(schedule -> Math.abs(schedule.getPrecondition_time() - desiredPreConditioningTime) < 5)
                .filter(schedule -> fleetApiService.decodeDaysOfWeek(schedule.getDays_of_week()).contains(dayOfWeek))
                .findFirst();
        String emailToAssign = null;
        if (event.getAttendees() != null) {
            List<EventAttendee> validAttendees = event.getAttendees().stream().filter(e ->
                    vinToEmailMap.entrySet().stream()
                            .anyMatch(e2 -> e2.getValue().equals(e.getEmail()))).toList();
            if (validAttendees.size() == 1) {
                emailToAssign = validAttendees.get(0).getEmail();
            }
        }
        if (emailToAssign == null) {
            boolean isCreatorValid = vinToEmailMap.entrySet().stream()
                    .anyMatch(e2 -> e2.getValue().equals(event.getCreator().getEmail()));
            if (isCreatorValid) {
                emailToAssign = event.getCreator().getEmail();
            } else {
                log.debug("No valid attendees found for calendar event: {}", event.getId());
                RemovePreconditionSchedule removePreconditionSchedule = new RemovePreconditionSchedule();
                removePreconditionSchedule.setId(entity.get().getPreconditionId());
                removePreconditionSchedule.setWait_for_completion(true);
                boolean deleted = fleetApiService.deletePreconditioningEntry(vinOfEntity, removePreconditionSchedule);
                if (deleted) calendarPreConditionLinkRepository.delete(entity.get());
                return false;
            }
        }
        String finalEmailToAssign = emailToAssign;
        String vin = vinToEmailMap.entrySet().stream()
                .filter(e2 -> e2.getValue().equals(finalEmailToAssign)).findFirst().get().getKey();
        boolean switchVin = !vinOfEntity.equals(vin);
        if (switchVin) {
            RemovePreconditionSchedule removePreconditionSchedule = new RemovePreconditionSchedule();
            removePreconditionSchedule.setId(entity.get().getPreconditionId());
            removePreconditionSchedule.setWait_for_completion(true);
            boolean deleted = fleetApiService.deletePreconditioningEntry(vinOfEntity, removePreconditionSchedule);
            if (deleted) {
                calendarPreConditionLinkRepository.delete(entity.get());
                return true;
            }
            return false;
        }
        if (!currentSchedule.isPresent()) {
            ModifyPreconditioningScheduleBody modifyPreconditioningScheduleBody =
                    new ModifyPreconditioningScheduleBody();
            modifyPreconditioningScheduleBody.setId(entity.get().getPreconditionId());
            modifyPreconditioningScheduleBody.setLat(homeLatitude);
            modifyPreconditioningScheduleBody.setLon(homeLongitude);
            modifyPreconditioningScheduleBody.setEnabled(true);
            modifyPreconditioningScheduleBody.setOne_time(true);
            modifyPreconditioningScheduleBody.setWait_for_completion(true);
            modifyPreconditioningScheduleBody.setPrecondition_time(desiredPreConditioningTime);
            modifyPreconditioningScheduleBody.setDays_of_week(dayOfWeek.name().toUpperCase().charAt(0) +
                    dayOfWeek.name().substring(1).toLowerCase());
            fleetApiService.addPreconditioningEntry(vinOfEntity, modifyPreconditioningScheduleBody);
        }
        return false;
    }
}
