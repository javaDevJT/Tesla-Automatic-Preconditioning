package com.jtdev.teslaautomaticpreconditioning.fleetapi;

import lombok.Data;

@Data
public class AddPreconditioningScheduleBody {
    private boolean wait_for_completion;
    private String days_of_week;
    private boolean enabled;
    private boolean one_time;
    private Integer precondition_time;
    private double lat;
    private double lon;
}
