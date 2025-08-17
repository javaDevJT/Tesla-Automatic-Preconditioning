package com.jtdev.teslaautomaticpreconditioning.repository;

import com.jtdev.teslaautomaticpreconditioning.entity.CalendarPreConditionLinkEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CalendarPreConditionLinkRepository extends JpaRepository<CalendarPreConditionLinkEntity, UUID> {
    Optional<CalendarPreConditionLinkEntity> findByCalendarId(String calendarId);
    Optional<CalendarPreConditionLinkEntity> findByPreconditionId(long preconditionId);
    boolean existsById(UUID uuid);

    boolean existsByCalendarId(String calendarId);
}
