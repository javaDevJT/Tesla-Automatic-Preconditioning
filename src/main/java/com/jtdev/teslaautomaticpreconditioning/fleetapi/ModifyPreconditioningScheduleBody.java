package com.jtdev.teslaautomaticpreconditioning.fleetapi;

import lombok.Data;

@Data
public class ModifyPreconditioningScheduleBody extends AddPreconditioningScheduleBody {
    private long id;
}
