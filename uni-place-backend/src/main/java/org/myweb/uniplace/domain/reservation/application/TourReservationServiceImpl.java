// 경로: org/myweb/uniplace/domain/reservation/application/TourReservationServiceImpl.java
package org.myweb.uniplace.domain.reservation.application;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;

import org.myweb.uniplace.domain.property.domain.entity.Building;
import org.myweb.uniplace.domain.property.domain.entity.Room;
import org.myweb.uniplace.domain.property.domain.enums.RoomStatus;
import org.myweb.uniplace.domain.property.repository.BuildingRepository;
import org.myweb.uniplace.domain.property.repository.RoomRepository;

import org.myweb.uniplace.domain.reservation.api.dto.request.CancelTourReservationRequest;
import org.myweb.uniplace.domain.reservation.api.dto.request.CreateTourReservationRequest;
import org.myweb.uniplace.domain.reservation.api.dto.request.LookupTourReservationRequest;
import org.myweb.uniplace.domain.reservation.api.dto.response.TourReservableResponse;
import org.myweb.uniplace.domain.reservation.api.dto.response.TourReservationResponse;
import org.myweb.uniplace.domain.reservation.domain.entity.TourReservationEntity;
import org.myweb.uniplace.domain.reservation.domain.enums.TourFixedSlot;
import org.myweb.uniplace.domain.reservation.domain.enums.TourStatus;
import org.myweb.uniplace.domain.reservation.domain.policy.ReservationValidator;
import org.myweb.uniplace.domain.reservation.domain.policy.TourReservationConflictPolicy;
import org.myweb.uniplace.domain.reservation.repository.TourReservationRepository;

import org.myweb.uniplace.domain.notification.application.NotificationService;
import org.myweb.uniplace.domain.notification.domain.enums.NotificationType;
import org.myweb.uniplace.domain.notification.domain.enums.TargetType;

import org.myweb.uniplace.global.exception.BusinessException;
import org.myweb.uniplace.global.exception.ErrorCode;
import org.myweb.uniplace.global.security.AuthUser;
import org.myweb.uniplace.global.slack.SlackNotificationService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import org.myweb.uniplace.domain.ownership.application.publicapi.OwnershipAccess;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class TourReservationServiceImpl implements TourReservationService {

    private final TourReservationRepository tourReservationRepository;
    private final BuildingRepository buildingRepository;
    private final RoomRepository roomRepository;
    private final OwnershipAccess ownership;
    private final JdbcTemplate jdbc;

    @PersistenceContext
    private EntityManager entityManager;

    private final ReservationValidator reservationValidator;
    private final TourReservationConflictPolicy reservationConflictPolicy;

    private final NotificationService notificationService;
    private final SlackNotificationService slackNotificationService;

    private static final List<TourStatus> INACTIVE = List.of(TourStatus.cancelled, TourStatus.ended);

    @Override
    public TourReservationResponse create(CreateTourReservationRequest request) {
        requireConsumerOrGuest();
        if (request == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST);
        }

        reservationValidator.validateTourTime(request.getTourStartAt(), request.getTourEndAt());
        reservationValidator.validateTourFixedSlot(request.getTourStartAt(), request.getTourEndAt());

        lockWrites();

        Building building = buildingRepository.findById(request.getBuildingId())
                .orElseThrow(() -> new BusinessException(ErrorCode.BUILDING_NOT_FOUND));

        // ✅ 삭제된 건물로 투어 예약 불가
        if (building.isDeleted()) {
            throw new BusinessException(ErrorCode.BUILDING_NOT_FOUND);
        }

        Room room = roomRepository.findById(request.getRoomId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));

        // ✅ 삭제된 방으로 투어 예약 불가
        if (room.isDeleted()) {
            throw new BusinessException(ErrorCode.ROOM_NOT_FOUND);
        }

        if (room.getBuilding() == null || !building.getBuildingId().equals(room.getBuilding().getBuildingId())) {
            throw new BusinessException(ErrorCode.TOUR_RESERVATION_BUILDING_ROOM_MISMATCH);
        }

        ownership.requireVerifiedRentalRoom(room.getRoomId());

        if (room.getRoomSt() != RoomStatus.available) {
            throw new BusinessException(ErrorCode.TOUR_RESERVATION_ROOM_UNAVAILABLE);
        }

        reservationConflictPolicy.validateDuplicateTelTime(
                request.getTourTel(),
                request.getTourStartAt(),
                request.getTourEndAt()
        );

        reservationConflictPolicy.validateRoomConflict(
                room.getRoomId(),
                request.getTourStartAt(),
                request.getTourEndAt()
        );

        TourReservationEntity saved = tourReservationRepository.saveAndFlush(
                TourReservationEntity.builder()
                        .building(building)
                        .room(room)
                        .tourStartAt(request.getTourStartAt())
                        .tourEndAt(request.getTourEndAt())
                        .tourNm(request.getTourNm())
                        .tourTel(request.getTourTel())
                        .tourPwd(request.getTourPwd())
                        .tourSt(TourStatus.requested)
                        .build()
        );

        String timeMsg = saved.getTourStartAt() + " ~ " + saved.getTourEndAt();

        notifyOwners(room.getRoomId(),
                NotificationType.TOUR_REQ.name(),
                "투어 예약 요청이 생성되었습니다. buildingId=" + building.getBuildingId()
                        + ", roomId=" + room.getRoomId()
                        + ", tel=" + saved.getTourTel()
                        + ", name=" + saved.getTourNm()
                        + ", time=" + timeMsg,
                null,
                TargetType.tour,
                saved.getTourId(),
                "/owner/reservations/tours"
        );
        
        notifySlackAfterCommit(saved, timeMsg);

        return TourReservationResponse.fromEntity(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<TourReservationResponse> lookup(LookupTourReservationRequest request, Pageable pageable) {
        requireConsumerOrGuest();
        Page<TourReservationEntity> page = tourReservationRepository.findByTourTelAndTourPwd(
                request.getTourTel(),
                request.getTourPwd(),
                pageable
        );

        if (page.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST);
        }

        return page.map(TourReservationResponse::fromEntity);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<TourReservationResponse> lookup(LookupTourReservationRequest request, TourStatus status, Pageable pageable) {
        requireConsumerOrGuest();
        if (status == null) {
            // Omitted status retains the legacy empty-page rejection and all-history query.
            return lookup(request, pageable);
        }
        Page<TourReservationEntity> page = tourReservationRepository.findByTourTelAndTourPwdAndTourSt(
                request.getTourTel(), request.getTourPwd(), status, pageable);
        if (page.getTotalElements() == 0 && !tourReservationRepository.existsByTourTelAndTourPwd(
                request.getTourTel(), request.getTourPwd())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST);
        }
        // A valid credential may have no rows in this status, or request a page beyond its last row.
        return page.map(TourReservationResponse::fromEntity);
    }

    @Override
    public TourReservationResponse cancel(Integer tourId, CancelTourReservationRequest request) {
        requireConsumerOrGuest();
        lockWrites();
        TourReservationEntity resv = tourReservationRepository.lockByTourIdAndTelephoneCredentials(
                tourId,
                request.getTourTel(),
                request.getTourPwd()
        );

        if (resv == null) {
            throw new BusinessException(ErrorCode.TOUR_RESERVATION_ALREADY_CANCELLED);
        }

        // A locking query may return an entity already held in the caller's first-level cache.
        entityManager.refresh(resv, LockModeType.PESSIMISTIC_WRITE);

        if (resv.getTourSt() == TourStatus.cancelled) {
            return TourReservationResponse.fromEntity(resv);
        }
        if (resv.getTourSt() == TourStatus.ended) {
            throw new BusinessException(ErrorCode.TOUR_RESERVATION_CANNOT_CANCEL);
        }

        resv.setTourSt(TourStatus.cancelled);
        tourReservationRepository.flush();

        String timeMsg = resv.getTourStartAt() + " ~ " + resv.getTourEndAt();

        notifyOwners(resv.getRoom().getRoomId(),
                NotificationType.TOUR_CAN.name(),
                "투어 예약이 취소되었습니다. tourId=" + resv.getTourId()
                        + ", tel=" + resv.getTourTel()
                        + ", name=" + resv.getTourNm()
                        + ", time=" + timeMsg,
                null,
                TargetType.tour,
                resv.getTourId(),
                "/owner/reservations/tours"
        );

        return TourReservationResponse.fromEntity(resv);
    }

    @Override
    public void changeStatus(Integer tourId, TourStatus status) {
        if (tourId == null || status == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST);
        }
        ownership.requireOwner();
        lockWrites();
        TourReservationEntity reservation = tourReservationRepository.lockByTourId(tourId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOUR_STATUS_NOT_FOUND));
        entityManager.refresh(reservation, LockModeType.PESSIMISTIC_WRITE);
        ownership.requireCanManageRoom(reservation.getRoom().getRoomId());

        // Restoring inactive history occupies time again; active-to-active changes must not conflict with themselves.
        if (INACTIVE.contains(reservation.getTourSt()) && !INACTIVE.contains(status)) {
            reservationConflictPolicy.validateDuplicateTelTime(
                    reservation.getTourTel(), reservation.getTourStartAt(), reservation.getTourEndAt());
            reservationConflictPolicy.validateRoomConflict(
                    reservation.getRoom().getRoomId(), reservation.getTourStartAt(), reservation.getTourEndAt());
        }
        reservation.setTourSt(status);
        tourReservationRepository.saveAndFlush(reservation);
    }

    private void requireConsumerOrGuest() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken) return;
        if (!authentication.isAuthenticated() || !(authentication.getPrincipal() instanceof AuthUser user)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if ("MEMBER".equals(user.getAccountKind())) ownership.requireMember();
        else if (!"GUEST".equals(user.getAccountKind())) throw new BusinessException(ErrorCode.FORBIDDEN);
    }

    private void lockWrites() {
        // JdbcTemplate participates in this JPA transaction without flushing stale managed rows first.
        // The seeded row must exist: do not silently continue or create it on demand.
        Integer guard = jdbc.queryForObject(
                "SELECT guard_id FROM tour_reservation_write_guard WHERE guard_id = 1 FOR UPDATE", Integer.class);
        if (!Integer.valueOf(1).equals(guard)) {
            throw new IllegalStateException("Tour reservation write guard is missing");
        }
    }

    private void notifyOwners(Integer roomId, String code, String message, String sender,
                              TargetType target, Integer targetId, String url) {
        for (String ownerId : ownership.eligibleOwnerIdsForRoom(roomId)) {
            notificationService.notifyUser(ownerId, code, message, sender, target, targetId, url);
        }
    }

    private void notifySlackAfterCommit(TourReservationEntity saved, String timeMsg) {
        Integer id = saved.getTourId();
        String name = saved.getTourNm();
        String telephone = saved.getTourTel();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    slackNotificationService.sendTourReservationAlert(id, name, telephone, timeMsg);
                } catch (RuntimeException failure) {
                    // A delivery failure must not turn an already committed reservation into an error response.
                    log.warn("Tour Slack alert failed after commit. tourId={}, type={}", id,
                            failure.getClass().getSimpleName());
                }
            }
        });
    }

    @Override
    @Transactional(readOnly = true)
    public TourReservableResponse reservableSlots(Integer buildingId, Integer roomId, LocalDate date) {

        if (buildingId == null || roomId == null || date == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST);
        }

        Building building = buildingRepository.findById(buildingId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BUILDING_NOT_FOUND));

        // ✅ 삭제된 건물 슬롯 조회 불가
        if (building.isDeleted()) {
            throw new BusinessException(ErrorCode.BUILDING_NOT_FOUND);
        }

        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));

        // ✅ 삭제된 방 슬롯 조회 불가
        if (room.isDeleted()) {
            throw new BusinessException(ErrorCode.ROOM_NOT_FOUND);
        }

        if (room.getBuilding() == null || !building.getBuildingId().equals(room.getBuilding().getBuildingId())) {
            throw new BusinessException(ErrorCode.TOUR_RESERVATION_BUILDING_ROOM_MISMATCH);
        }

        ownership.requirePublicBuilding(buildingId);
        ownership.requirePublicRoom(roomId);

        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime dayEnd   = date.plusDays(1).atStartOfDay();

        List<TourReservationEntity> reserved = tourReservationRepository.findActiveInDayRange(
                roomId, INACTIVE, dayStart, dayEnd
        );

        List<TourReservableResponse.TimeSlotResponse> available = Arrays.stream(TourFixedSlot.values())
                .map(slot -> {
                    LocalDateTime startAt = LocalDateTime.of(date, slot.getStart());
                    LocalDateTime endAt   = LocalDateTime.of(date, slot.getEnd());
                    return TourReservableResponse.TimeSlotResponse.builder()
                            .label(slot.label())
                            .startAt(startAt)
                            .endAt(endAt)
                            .build();
                })
                .filter(ts -> reserved.stream().noneMatch(reservation ->
                        ts.getStartAt().isBefore(reservation.getTourEndAt())
                                && ts.getEndAt().isAfter(reservation.getTourStartAt())))
                .toList();

        return TourReservableResponse.builder()
                .buildingId(buildingId)
                .roomId(roomId)
                .availableSlots(available)
                .build();
    }

}
