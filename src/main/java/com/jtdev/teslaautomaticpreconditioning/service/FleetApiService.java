package com.jtdev.teslaautomaticpreconditioning.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jtdev.teslaautomaticpreconditioning.fleetapi.AddPreconditioningScheduleBody;
import com.jtdev.teslaautomaticpreconditioning.fleetapi.FleetApi;
import com.jtdev.teslaautomaticpreconditioning.fleetapi.RemovePreconditionSchedule;
import com.jtdev.teslaautomaticpreconditioning.fleetapi.VehicleData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.util.EnumSet;
import java.util.Set;

@Service
public class FleetApiService {

    @Autowired
    private FleetApi fleetApi;

    /**
     * Convert Tesla's day-of-week bitmask into a Java Set of DayOfWeek values.
     * <p>
     * Tesla mapping:
     * 1 = Sunday
     * 2 = Monday
     * 4 = Tuesday
     * 8 = Wednesday
     * 16 = Thursday
     * 32 = Friday
     * 64 = Saturday
     */
    public Set<DayOfWeek> decodeDaysOfWeek(int mask) {
        EnumSet<DayOfWeek> out = EnumSet.noneOf(DayOfWeek.class);
        if ((mask & 1) != 0) out.add(DayOfWeek.SUNDAY);
        if ((mask & 2) != 0) out.add(DayOfWeek.MONDAY);
        if ((mask & 4) != 0) out.add(DayOfWeek.TUESDAY);
        if ((mask & 8) != 0) out.add(DayOfWeek.WEDNESDAY);
        if ((mask & 16) != 0) out.add(DayOfWeek.THURSDAY);
        if ((mask & 32) != 0) out.add(DayOfWeek.FRIDAY);
        if ((mask & 64) != 0) out.add(DayOfWeek.SATURDAY);
        return out;
    }

    public int encodeDaysOfWeek(Set<DayOfWeek> days) {
        int mask = 0;
        if (days.contains(DayOfWeek.SUNDAY)) mask |= 1;
        if (days.contains(DayOfWeek.MONDAY)) mask |= 2;
        if (days.contains(DayOfWeek.TUESDAY)) mask |= 4;
        if (days.contains(DayOfWeek.WEDNESDAY)) mask |= 8;
        if (days.contains(DayOfWeek.THURSDAY)) mask |= 16;
        if (days.contains(DayOfWeek.FRIDAY)) mask |= 32;
        if (days.contains(DayOfWeek.SATURDAY)) mask |= 64;
        return mask;
    }

    public VehicleData getVehicleData(String vin) throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(fleetApi.tessieGetVehicle(vin), VehicleData.class);
    }

    public VehicleData getVehicleDataNoCache(String vin) throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(fleetApi.tessieGetVehicleNoCache(vin), VehicleData.class);
    }

    public boolean addPreconditioningEntry(String vin, AddPreconditioningScheduleBody addPreconditioningScheduleBody) throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        String body = mapper.writeValueAsString(addPreconditioningScheduleBody);
        return fleetApi.commandAddPreconditionSchedule(vin, body).contains("true");
    }

    public boolean deletePreconditioningEntry(String vin, RemovePreconditionSchedule removePreconditionSchedule) throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        return fleetApi.tessieRemovePreconditionSchedule(vin, mapper.writeValueAsString(removePreconditionSchedule)).contains("true");
    }

    public boolean startMaxDefrost(String vin) {
        return fleetApi.tessieStartDefrost(vin).contains("true");
    }
}
