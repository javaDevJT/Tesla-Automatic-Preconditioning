package com.jtdev.teslaautomaticpreconditioning.repository;

import com.jtdev.teslaautomaticpreconditioning.entity.CalendarPreConditionLinkEntity;
import com.jtdev.teslaautomaticpreconditioning.entity.PreconditioningStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CalendarPreConditionLinkRepository extends JpaRepository<CalendarPreConditionLinkEntity, UUID> {
    Optional<CalendarPreConditionLinkEntity> findFirstByCalendarId(String calendarId);
    Optional<CalendarPreConditionLinkEntity> findByPreconditionId(long preconditionId);
    boolean existsById(UUID uuid);
    List<CalendarPreConditionLinkEntity> findAllByDeleted(boolean isDeleted);
    List<CalendarPreConditionLinkEntity> findAllByCalendarIdAndDeleted(String calendarId, boolean isDeleted);
    boolean existsByCalendarId(String calendarId);

    List<CalendarPreConditionLinkEntity> findAllByStatusIsNot(PreconditioningStatus status);
}
