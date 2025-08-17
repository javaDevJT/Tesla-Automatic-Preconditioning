package com.jtdev.teslaautomaticpreconditioning.entity;


import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Table
@Entity
@NoArgsConstructor
@AllArgsConstructor
public class CalendarPreConditionLinkEntity {

    @Id
    @GeneratedValue
    private UUID uuid;
    @Column
    private String vin;
    @Column
    private String calendarId;
    @Column
    private long unixStartTime;
    @Column
    private String attendeeEmail;
    @Column
    private long preconditionId;
    @Column
    private boolean deleted;
    @Column
    @Enumerated(EnumType.STRING)
    private PreconditioningStatus status = PreconditioningStatus.PENDING;
    
    // Preconditioning schedule parameters to avoid unnecessary vehicle queries
    @Column
    private Integer storedPreconditionTime; // Minutes from midnight
    
    @Column
    private String storedDayOfWeek; // Day of week string
    
    @Column
    private Double storedLatitude; // Location latitude
    
    @Column
    private Double storedLongitude; // Location longitude
    
    @Column
    private Long lastVerifiedTimestamp; // When we last confirmed it was on vehicle
    
    @Column
    private String eventSummary; // Name/title of the calendar event
}
