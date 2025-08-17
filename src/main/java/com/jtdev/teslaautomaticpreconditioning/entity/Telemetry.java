package com.jtdev.teslaautomaticpreconditioning.entity;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Date;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Telemetry {
    private Data data;
    private Date createdAt;
    private String vin;
    private String state;
    private ArrayList<Alert> alerts;
    @JsonAnySetter
    private Map<Object, Object> other;

    @lombok.Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Data{
        @JsonProperty("EstBatteryRange")
        private Double estBatteryRange;
        @JsonProperty("InsideTemp")
        private Double insideTemp;
        @JsonProperty("OutsideTemp")
        private Double outsideTemp;
        @JsonProperty("BatteryLevel")
        private Double batteryLevel;
        @JsonProperty("Location")
        private Location location;
        @JsonAnySetter
        private Map<Object, Object> other;
    }
    @lombok.Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Location{
        private Double latitude;
        private Double longitude;
        @JsonAnySetter
        private Map<Object, Object> other;
    }

    @lombok.Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Alert{
        private String name;
        private ArrayList<String> audiences;
        private Date startedAt;
        private Date endedAt;
        private String description;
        private String customerFacingMessage1;
        @JsonAnySetter
        private Map<Object, Object> other;
    }
}
