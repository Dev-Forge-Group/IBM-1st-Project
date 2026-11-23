// Repository
// 경로: org/myweb/uniplace/domain/reservation/repository/TourReservationRepository.java
package org.myweb.uniplace.domain.reservation.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.myweb.uniplace.domain.reservation.domain.entity.TourReservationEntity;
import org.myweb.uniplace.domain.reservation.domain.enums.TourStatus;
import org.myweb.uniplace.domain.reservation.domain.policy.TourTelephone;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface TourReservationRepository extends JpaRepository<TourReservationEntity, Integer> {

    String TELEPHONE_MATCH = """
        (t.tourTel = :tourTel
         or (:canonicalTel is not null
             and function('hex', replace(t.tourTel, '-', '')) = function('hex', :canonicalTel)))
        """;

    Page<TourReservationEntity> findByRoom_RoomIdIn(Collection<Integer> roomIds, Pageable pageable);

    // Keep the legacy raw comparison. The new presentation branch compares phone and PIN bytes,
    // so MySQL's case/accent-insensitive collation cannot introduce extra credential aliases.
    String CREDENTIAL_MATCH = """
        t.tourPwd = :tourPwd
        and (t.tourTel = :tourTel
             or (:canonicalTel is not null
                 and function('hex', replace(t.tourTel, '-', '')) = function('hex', :canonicalTel)
                 and function('hex', t.tourPwd) = function('hex', :tourPwd)))
        """;

    @Query("""
        select count(t) > 0
          from TourReservationEntity t
         where t.room.roomId = :roomId
           and t.tourSt not in :inactive
           and (:startAt < t.tourEndAt and :endAt > t.tourStartAt)
    """)
    boolean existsRoomTimeConflict(
            @Param("roomId") Integer roomId,
            @Param("inactive") Collection<TourStatus> inactive,
            @Param("startAt") LocalDateTime startAt,
            @Param("endAt") LocalDateTime endAt
    );

    // Locking reads see committed changes even when a REQUIRED caller already opened an RR snapshot.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select t from TourReservationEntity t
         where t.room.roomId = :roomId and t.tourSt not in :inactive
           and :startAt < t.tourEndAt and :endAt > t.tourStartAt
        """)
    List<TourReservationEntity> findRoomConflictsForUpdate(
            @Param("roomId") Integer roomId, @Param("inactive") Collection<TourStatus> inactive,
            @Param("startAt") LocalDateTime startAt, @Param("endAt") LocalDateTime endAt,
            Pageable pageable);

    @Query("""
        select t
          from TourReservationEntity t
         where t.room.roomId = :roomId
           and t.tourSt not in :inactive
           and t.tourStartAt < :dayEnd
           and t.tourEndAt   > :dayStart
    """)
    List<TourReservationEntity> findActiveInDayRange(
            @Param("roomId") Integer roomId,
            @Param("inactive") Collection<TourStatus> inactive,
            @Param("dayStart") LocalDateTime dayStart,
            @Param("dayEnd") LocalDateTime dayEnd
    );

    default Page<TourReservationEntity> findByTourTelAndTourPwdAndTourStNotIn(
            String tourTel,
            String tourPwd,
            Collection<TourStatus> inactive,
            Pageable pageable
    ) {
        return findCompatibleActiveTelephone(tourTel, tourPwd,
                TourTelephone.canonicalForCredentials(tourTel, tourPwd), inactive, pageable);
    }

    @Query(value = "select t from TourReservationEntity t where " + CREDENTIAL_MATCH + " and t.tourSt not in :inactive",
           countQuery = "select count(t) from TourReservationEntity t where " + CREDENTIAL_MATCH + " and t.tourSt not in :inactive")
    Page<TourReservationEntity> findCompatibleActiveTelephone(
            @Param("tourTel") String tourTel, @Param("tourPwd") String tourPwd,
            @Param("canonicalTel") String canonicalTel, @Param("inactive") Collection<TourStatus> inactive,
            Pageable pageable);

    // 전체 상태 조회 (취소/완료 포함)
    default Page<TourReservationEntity> findByTourTelAndTourPwd(
            String tourTel,
            String tourPwd,
            Pageable pageable
    ) {
        return findCompatibleTelephone(tourTel, tourPwd,
                TourTelephone.canonicalForCredentials(tourTel, tourPwd), pageable);
    }

    @Query(value = "select t from TourReservationEntity t where " + CREDENTIAL_MATCH,
           countQuery = "select count(t) from TourReservationEntity t where " + CREDENTIAL_MATCH)
    Page<TourReservationEntity> findCompatibleTelephone(
            @Param("tourTel") String tourTel, @Param("tourPwd") String tourPwd,
            @Param("canonicalTel") String canonicalTel, Pageable pageable);

    default Page<TourReservationEntity> findByTourTelAndTourPwdAndTourSt(
            String tourTel, String tourPwd, TourStatus status, Pageable pageable) {
        return findCompatibleTelephoneByStatus(tourTel, tourPwd,
                TourTelephone.canonicalForCredentials(tourTel, tourPwd), status, pageable);
    }

    @Query(value = "select t from TourReservationEntity t where " + CREDENTIAL_MATCH + " and t.tourSt = :status",
           countQuery = "select count(t) from TourReservationEntity t where " + CREDENTIAL_MATCH + " and t.tourSt = :status")
    Page<TourReservationEntity> findCompatibleTelephoneByStatus(
            @Param("tourTel") String tourTel, @Param("tourPwd") String tourPwd,
            @Param("canonicalTel") String canonicalTel, @Param("status") TourStatus status,
            Pageable pageable);

    default boolean existsByTourTelAndTourPwd(String tourTel, String tourPwd) {
        return existsCompatibleTelephone(tourTel, tourPwd, TourTelephone.canonicalForCredentials(tourTel, tourPwd));
    }

    @Query("select count(t) > 0 from TourReservationEntity t where " + CREDENTIAL_MATCH)
    boolean existsCompatibleTelephone(
            @Param("tourTel") String tourTel, @Param("tourPwd") String tourPwd,
            @Param("canonicalTel") String canonicalTel);

    default TourReservationEntity findByTourIdAndTourTelAndTourPwd(Integer tourId, String tourTel, String tourPwd) {
        return findCompatibleTelephoneById(tourId, tourTel, tourPwd,
                TourTelephone.canonicalForCredentials(tourTel, tourPwd));
    }

    @Query("select t from TourReservationEntity t where t.tourId = :tourId and " + CREDENTIAL_MATCH)
    TourReservationEntity findCompatibleTelephoneById(
            @Param("tourId") Integer tourId, @Param("tourTel") String tourTel,
            @Param("tourPwd") String tourPwd, @Param("canonicalTel") String canonicalTel);

    default TourReservationEntity lockByTourIdAndTelephoneCredentials(Integer tourId, String tourTel, String tourPwd) {
        return lockCompatibleTelephoneById(tourId, tourTel, tourPwd,
                TourTelephone.canonicalForCredentials(tourTel, tourPwd));
    }

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TourReservationEntity t where t.tourId = :tourId and " + CREDENTIAL_MATCH)
    TourReservationEntity lockCompatibleTelephoneById(
            @Param("tourId") Integer tourId, @Param("tourTel") String tourTel,
            @Param("tourPwd") String tourPwd, @Param("canonicalTel") String canonicalTel);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TourReservationEntity t where t.tourId = :tourId")
    Optional<TourReservationEntity> lockByTourId(@Param("tourId") Integer tourId);
    
    default boolean existsByTourTelAndTourStartAtAndTourEndAtAndTourStNotIn(
            String tourTel,
            LocalDateTime tourStartAt,
            LocalDateTime tourEndAt,
            Collection<TourStatus> inactive
    ) {
        return existsCompatibleTelephoneTime(tourTel, TourTelephone.canonical(tourTel), tourStartAt, tourEndAt, inactive);
    }

    @Query("select count(t) > 0 from TourReservationEntity t where " + TELEPHONE_MATCH
            + " and t.tourStartAt = :startAt and t.tourEndAt = :endAt and t.tourSt not in :inactive")
    boolean existsCompatibleTelephoneTime(
            @Param("tourTel") String tourTel, @Param("canonicalTel") String canonicalTel,
            @Param("startAt") LocalDateTime startAt, @Param("endAt") LocalDateTime endAt,
            @Param("inactive") Collection<TourStatus> inactive);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TourReservationEntity t where " + TELEPHONE_MATCH
            + " and t.tourStartAt = :startAt and t.tourEndAt = :endAt and t.tourSt not in :inactive")
    List<TourReservationEntity> findTelephoneTimeConflictsForUpdate(
            @Param("tourTel") String tourTel, @Param("canonicalTel") String canonicalTel,
            @Param("startAt") LocalDateTime startAt, @Param("endAt") LocalDateTime endAt,
            @Param("inactive") Collection<TourStatus> inactive, Pageable pageable);
    
}
