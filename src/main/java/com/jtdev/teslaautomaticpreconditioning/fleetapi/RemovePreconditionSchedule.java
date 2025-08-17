package com.jtdev.teslaautomaticpreconditioning.fleetapi;

import lombok.Data;

@Data
public class RemovePreconditionSchedule {
    private long id;
    private boolean wait_for_completion;
}
