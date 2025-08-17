package com.jtdev.teslaautomaticpreconditioning.entity;

public enum PreconditioningStatus {
    PENDING,     // Schedule request submitted to Tesla but not confirmed
    ACTIVE,      // Schedule confirmed and active in Tesla system  
    FAILED,      // Schedule creation failed permanently
    EXPIRED      // Event time passed before schedule could be created
}
