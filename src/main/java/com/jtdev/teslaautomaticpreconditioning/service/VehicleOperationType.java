package com.jtdev.teslaautomaticpreconditioning.service;

/**
 * Types of vehicle operations to determine wake requirements
 */
public enum VehicleOperationType {
    // Read-only operations (can use cached data)
    GET_LOCATION(false),
    GET_SCHEDULES(false), 
    GET_DRIVE_STATE(false),
    GET_CLIMATE_STATE(false),
    VERIFY_SCHEDULE_EXISTS(false),
    
    // Write operations (require wake and fresh data)
    ADD_SCHEDULE(true),
    DELETE_SCHEDULE(true),
    START_DEFROST(true),
    STOP_DEFROST(true),
    START_CLIMATE(true),
    STOP_CLIMATE(true);
    
    private final boolean requiresWake;
    
    VehicleOperationType(boolean requiresWake) {
        this.requiresWake = requiresWake;
    }
    
    public boolean requiresWake() {
        return requiresWake;
    }
}
