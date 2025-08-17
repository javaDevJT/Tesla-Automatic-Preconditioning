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
}
