// 경로: org/myweb/uniplace/domain/reservation/application/SpaceReservationServiceImpl.java
package org.myweb.uniplace.domain.reservation.application;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

import org.myweb.uniplace.domain.property.api.dto.request.SpaceSearchRequest;
import org.myweb.uniplace.domain.property.api.dto.response.SpaceResponse;
import org.myweb.uniplace.domain.property.application.SpaceService;
import org.myweb.uniplace.domain.property.domain.entity.Building;
import org.myweb.uniplace.domain.property.domain.entity.CommonSpace;
import org.myweb.uniplace.domain.property.repository.BuildingRepository;
import org.myweb.uniplace.domain.property.repository.SpaceRepository;
import org.myweb.uniplace.domain.contract.repository.ContractRepository;
import org.myweb.uniplace.domain.ownership.application.publicapi.OwnershipAccess;

import org.myweb.uniplace.domain.reservation.api.dto.request.CancelSpaceReservationRequest;
import org.myweb.uniplace.domain.reservation.api.dto.request.CreateSpaceReservationRequest;
import org.myweb.uniplace.domain.reservation.api.dto.response.SpaceReservableResponse;
import org.myweb.uniplace.domain.reservation.api.dto.response.SpaceReservationBuildingResponse;
import org.myweb.uniplace.domain.reservation.api.dto.response.SpaceReservationResponse;
import org.myweb.uniplace.domain.reservation.domain.entity.SpaceReservationEntity;
import org.myweb.uniplace.domain.reservation.domain.enums.SpaceFixedSlot;
import org.myweb.uniplace.domain.reservation.domain.enums.SpaceReservationStatus;
import org.myweb.uniplace.domain.reservation.domain.policy.ReservationValidator;
import org.myweb.uniplace.domain.reservation.domain.policy.SpaceReservationConflictPolicy;
import org.myweb.uniplace.domain.contract.domain.enums.ContractStatus;
import org.myweb.uniplace.domain.reservation.repository.SpaceReservationRepository;

import org.myweb.uniplace.domain.user.domain.entity.User;
import org.myweb.uniplace.domain.user.domain.enums.UserStatus;
import org.myweb.uniplace.domain.user.repository.UserRepository;

import org.myweb.uniplace.domain.notification.application.NotificationService;
import org.myweb.uniplace.domain.notification.domain.enums.NotificationType;
import org.myweb.uniplace.domain.notification.domain.enums.TargetType;

import org.myweb.uniplace.global.exception.BusinessException;
import org.myweb.uniplace.global.exception.ErrorCode;
import org.myweb.uniplace.global.security.AuthUser;
import org.myweb.uniplace.global.slack.SlackNotificationService;
import org.springframework.data.domain.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class SpaceReservationServiceImpl implements SpaceReservationService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final SpaceReservationRepository spaceReservationRepository;
    private final BuildingRepository buildingRepository;
    private final SpaceRepository spaceRepository;
    private final ContractRepository contractRepository;
    private final OwnershipAccess ownership;

    private final SpaceService spaceService;
    private final UserRepository userRepository;

    private final ReservationValidator reservationValidator;
    private final SpaceReservationConflictPolicy spaceReservationConflictPolicy;

    // ✅ 알림
    private final NotificationService notificationService;
    private final SlackNotificationService slackNotificationService;

    private static final List<SpaceReservationStatus> INACTIVE =
            List.of(SpaceReservationStatus.cancelled, SpaceReservationStatus.ended);

    /* =========================
       ✅ RESERVABLE
       ========================= */

    @Override
    @Transactional(readOnly = true)
    public List<SpaceReservationBuildingResponse> reservableBuildings(AuthUser me) {
        requireMember(me);
        return contractRepository.findActiveResidentBuildings(me.getUserId(), ContractStatus.active, LocalDate.now(KST))
                .stream()
                .filter(building -> ownership.isPublicBuilding(building.getBuildingId()))
                .map(building -> new SpaceReservationBuildingResponse(building.getBuildingId(),
                        building.getBuildingNm(), building.getBuildingAddr()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SpaceReservableResponse> reservableSpaces(Integer buildingId, LocalDate date, Pageable pageable) {

        if (buildingId == null || date == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST);
        }

        Building reservableBuilding = buildingRepository.findById(buildingId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BUILDING_NOT_FOUND));

        // ✅ 삭제된 건물의 공간 예약 가능 목록 조회 불가
        if (reservableBuilding.isDeleted()) {
            throw new BusinessException(ErrorCode.BUILDING_NOT_FOUND);
        }
        ownership.requirePublicBuilding(buildingId);

        SpaceSearchRequest request = SpaceSearchRequest.builder()
                .buildingId(buildingId)
                .build();

        Page<SpaceResponse> spaces = spaceService.searchPage(request, pageable);
        boolean verifiedOperator = !ownership.eligibleOwnerIdsForBuilding(buildingId).isEmpty();

        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime dayEnd = date.plusDays(1).atStartOfDay();

        return spaces.map(s -> {
            Integer spaceId = s.getSpaceId();
            var response = SpaceReservableResponse.builder()
                    .buildingId(buildingId)
                    .spaceId(spaceId)
                    .spaceNm(s.getSpaceNm())
                    .spaceCapacity(s.getSpaceCapacity());
            if (!verifiedOperator) {
                return response.availableSlots(List.of())
                        .unavailableReason(ErrorCode.SPACE_RESERVATION_OWNER_UNVERIFIED.getCode())
                        .build();
            }

            List<SpaceReservationEntity> reserved = spaceReservationRepository.findActiveInDayRange(
                    spaceId,
                    INACTIVE,
                    dayStart,
                    dayEnd
            );

            List<SpaceReservableResponse.TimeSlotResponse> available = Arrays.stream(SpaceFixedSlot.values())
                    .map(slot -> {
                        LocalDateTime startAt = LocalDateTime.of(date, slot.getStart());
                        LocalDateTime endAt = LocalDateTime.of(date, slot.getEnd());
                        return SpaceReservableResponse.TimeSlotResponse.builder()
                                .label(slot.label())
                                .startAt(startAt)
                                .endAt(endAt)
                                .build();
                    })
                    .filter(ts -> reserved.stream().noneMatch(reservation ->
                            ts.getStartAt().isBefore(reservation.getSrEndAt())
                                    && ts.getEndAt().isAfter(reservation.getSrStartAt())))
                    .toList();

            return response.availableSlots(available).build();
        });
    }

    /* =========================
       ✅ CREATE
       ========================= */

    @Override
    public SpaceReservationResponse create(AuthUser me, CreateSpaceReservationRequest request) {

        requireMember(me);
        if (request == null) throw new BusinessException(ErrorCode.BAD_REQUEST);

        User user = userRepository.findById(me.getUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        // ✅ tenant만 예약 가능
        if (!ownership.effectiveAccess(me.getUserId()).roles().contains("TENANT")) {
            throw new BusinessException(ErrorCode.SPACE_RESERVATION_TENANT_ONLY);
        }
        if (user.getUserSt() != UserStatus.active || "Y".equalsIgnoreCase(user.getDeleteYN())) {
            throw new BusinessException(ErrorCode.SPACE_RESERVATION_USER_INACTIVE);
        }

        // ✅ 같은 건물에 active 계약이 있는 입주자만 예약 가능
        boolean hasActiveContract = contractRepository.existsActiveResidentContractByUserAndBuilding(
                me.getUserId(), request.getBuildingId(), ContractStatus.active, LocalDate.now(KST)
        );
        if (!hasActiveContract) {
            throw new BusinessException(ErrorCode.SPACE_RESERVATION_BUILDING_MISMATCH);
        }

        reservationValidator.validateSpaceTime(request.getSrStartAt(), request.getSrEndAt());
        reservationValidator.validateSpaceFixedSlot(request.getSrStartAt(), request.getSrEndAt());

        if (request.getSrNoPeople() == null || request.getSrNoPeople() < 1) {
            throw new BusinessException(ErrorCode.SPACE_RESERVATION_PEOPLE_INVALID);
        }

        Building building = buildingRepository.findById(request.getBuildingId())
                .orElseThrow(() -> new BusinessException(ErrorCode.BUILDING_NOT_FOUND));

        // ✅ 삭제된 건물로 공간 예약 불가
        if (building.isDeleted()) {
            throw new BusinessException(ErrorCode.BUILDING_NOT_FOUND);
        }
        ownership.requirePublicBuilding(building.getBuildingId());
        if (ownership.eligibleOwnerIdsForBuilding(building.getBuildingId()).isEmpty()) {
            throw new BusinessException(ErrorCode.SPACE_RESERVATION_OWNER_UNVERIFIED);
        }

        CommonSpace space = spaceRepository.findById(request.getSpaceId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SPACE_NOT_FOUND));

        if (space.getBuilding() == null || !building.getBuildingId().equals(space.getBuilding().getBuildingId())) {
            throw new BusinessException(ErrorCode.SPACE_BUILDING_MISMATCH);
        }

        if (space.getSpaceCapacity() != null && request.getSrNoPeople() > space.getSpaceCapacity()) {
            throw new BusinessException(ErrorCode.SPACE_CAPACITY_EXCEEDED);
        }

        spaceReservationConflictPolicy.validateSpaceConflict(
                space.getSpaceId(),
                request.getSrStartAt(),
                request.getSrEndAt()
        );

        SpaceReservationEntity saved;
        try {
            saved = spaceReservationRepository.saveAndFlush(SpaceReservationEntity.builder()
                        .building(building)
                        .space(space)
                        .user(user)
                        .srStartAt(request.getSrStartAt())
                        .srEndAt(request.getSrEndAt())
                        .srNoPeople(request.getSrNoPeople())
                        .srSt(SpaceReservationStatus.requested)
                        .build());
        } catch (DataIntegrityViolationException failure) {
            if (isActiveSlotDuplicate(failure)) {
                throw new BusinessException(ErrorCode.SPACE_RESERVATION_TIME_CONFLICT);
            }
            throw failure;
        }

        // =========================
        // 알림(여기 라인에 넣기) - 예약 생성 직후
        // =========================
        String timeMsg = saved.getSrStartAt() + " ~ " + saved.getSrEndAt();

        // 1) 사용자 알림
        notificationService.notifyUser(
                user.getUserId(),
                NotificationType.SP_REQ.name(),
                "공간 예약 요청이 접수되었습니다. (" + timeMsg + ")",
                null,
                TargetType.space,
                saved.getReservationId(),
                "/space-reservations/my"
        );

        // 2) 관리자 알림
        notifyOwners(building.getBuildingId(),
                NotificationType.SP_REQ.name(),
                "공간 예약 요청이 생성되었습니다. userId=" + user.getUserId()
                        + ", buildingId=" + building.getBuildingId()
                        + ", spaceId=" + space.getSpaceId()
                        + ", time=" + timeMsg,
                user.getUserId(),
                TargetType.space,
                saved.getReservationId(),
                "/owner/reservations/spaces"
        );
        
        slackNotificationService.sendSpaceReservationAlert(
        	    saved.getReservationId(),
        	    user.getUserId(),
        	    space.getSpaceNm(),
        	    timeMsg
        );

        return SpaceReservationResponse.fromEntity(saved);
    }

    /* =========================
        USER CANCEL
       ========================= */

    @Override
    public SpaceReservationResponse cancel(AuthUser me, Integer reservationId) {

        requireMember(me);
        if (reservationId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST);
        }

        SpaceReservationEntity e = spaceReservationRepository.findById(reservationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST));

        if (e.getUser() == null || !me.getUserId().equals(e.getUser().getUserId())) {
            throw new BusinessException(ErrorCode.SPACE_RESERVATION_ACCESS_DENIED);
        }

        if (e.getSrSt() == SpaceReservationStatus.cancelled || e.getSrSt() == SpaceReservationStatus.ended) {
            return SpaceReservationResponse.fromEntity(e);
        }

        e.setSrSt(SpaceReservationStatus.cancelled);
        flushReservationChange();

        // =========================
        // ✅ 알림(여기 라인에 넣기) - 사용자 취소 직후
        // =========================
        String timeMsg = e.getSrStartAt() + " ~ " + e.getSrEndAt();

        // 1) 사용자 알림(자기 취소 확인)
        notificationService.notifyUser(
                e.getUser().getUserId(),
                NotificationType.SP_CAN.name(),
                "공간 예약이 취소되었습니다. (" + timeMsg + ")",
                null,
                TargetType.space,
                e.getReservationId(),
                "/space-reservations/my"
        );

        // 2) 관리자 알림
        notifyOwners(e.getBuilding().getBuildingId(),
                NotificationType.SP_CAN.name(),
                "사용자가 공간 예약을 취소했습니다. userId=" + e.getUser().getUserId()
                        + ", reservationId=" + e.getReservationId()
                        + ", time=" + timeMsg,
                e.getUser().getUserId(),
                TargetType.space,
                e.getReservationId(),
                "/owner/reservations/spaces"
        );

        return SpaceReservationResponse.fromEntity(e);
    }

    /* =========================
       ✅ MY RESERVATIONS
       ========================= */

    @Override
    @Transactional(readOnly = true)
    public Page<SpaceReservationResponse> myReservations(AuthUser me, SpaceReservationStatus status, Pageable pageable) {

        requireMember(me);

        Page<SpaceReservationEntity> page = status == null
                ? spaceReservationRepository.findByUser_UserId(me.getUserId(), pageable)
                : spaceReservationRepository.findByUser_UserIdAndSrSt(me.getUserId(), status, pageable);

        return page.map(SpaceReservationResponse::fromEntity);
    }

    /* =========================
       ✅ ADMIN
       ========================= */

    @Override
    @Transactional(readOnly = true)
    public Page<SpaceReservationResponse> adminSearch(
            AuthUser me,
            Integer buildingId,
            Integer spaceId,
            String userId,
            LocalDate date,
            Pageable pageable
    ) {
        requireOwner(me);

        LocalDateTime dayStart = null;
        LocalDateTime dayEnd = null;
        if (date != null) {
            dayStart = date.atStartOfDay();
            dayEnd = date.plusDays(1).atStartOfDay();
        }

        var buildingIds = ownership.manageableBuildingIds();
        if (buildingIds.isEmpty()) return Page.empty(pageable);
        final LocalDateTime from = dayStart;
        final LocalDateTime to = dayEnd;
        Page<SpaceReservationEntity> page = spaceReservationRepository.findAll((root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            predicates.add(root.get("building").get("buildingId").in(buildingIds));
            if (buildingId != null) predicates.add(cb.equal(root.get("building").get("buildingId"), buildingId));
            if (spaceId != null) predicates.add(cb.equal(root.get("space").get("spaceId"), spaceId));
            if (userId != null && !userId.isBlank()) predicates.add(cb.equal(root.get("user").get("userId"), userId));
            if (from != null) predicates.add(cb.and(cb.lessThan(root.get("srStartAt"), to), cb.greaterThan(root.get("srEndAt"), from)));
            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        }, pageable);

        return page.map(SpaceReservationResponse::fromEntity);
    }

    @Override
    @Transactional(readOnly = true)
    public SpaceReservationResponse adminDetail(AuthUser me, Integer reservationId) {
        requireOwner(me);
        if (reservationId == null) throw new BusinessException(ErrorCode.BAD_REQUEST);

        SpaceReservationEntity e = spaceReservationRepository.findById(reservationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SPACE_RESERVATION_NOT_FOUND));
        ownership.requireCanManageBuilding(e.getBuilding().getBuildingId());

        return SpaceReservationResponse.fromEntity(e);
    }

    @Override
    public SpaceReservationResponse adminConfirm(AuthUser me, Integer reservationId) {
        requireOwner(me);
        if (reservationId == null) throw new BusinessException(ErrorCode.BAD_REQUEST);

        SpaceReservationEntity e = spaceReservationRepository.findById(reservationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SPACE_RESERVATION_NOT_FOUND));
        ownership.requireCanManageBuilding(e.getBuilding().getBuildingId());

        if (e.getSrSt() == SpaceReservationStatus.confirmed) {
            return SpaceReservationResponse.fromEntity(e);
        }
        if (e.getSrSt() == SpaceReservationStatus.cancelled || e.getSrSt() == SpaceReservationStatus.ended) {
            throw new BusinessException(ErrorCode.SPACE_RESERVATION_CANNOT_CANCEL);
        }

        e.setSrSt(SpaceReservationStatus.confirmed);
        flushReservationChange();

        // =========================
        // 알림 - 관리자 확정 직후
        // =========================
        String timeMsg = e.getSrStartAt() + " ~ " + e.getSrEndAt();

        notificationService.notifyUser(
                e.getUser().getUserId(),
                NotificationType.SP_CFM.name(),
                "공간 예약이 확정되었습니다. (" + timeMsg + ")",
                me.getUserId(), // sender=admin
                TargetType.space,
                e.getReservationId(),
                "/space-reservations/my"
        );

        return SpaceReservationResponse.fromEntity(e);
    }

    @Override
    public SpaceReservationResponse adminEnd(AuthUser me, Integer reservationId) {
        requireOwner(me);
        if (reservationId == null) throw new BusinessException(ErrorCode.BAD_REQUEST);

        SpaceReservationEntity e = spaceReservationRepository.findById(reservationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SPACE_RESERVATION_NOT_FOUND));
        ownership.requireCanManageBuilding(e.getBuilding().getBuildingId());

        if (e.getSrSt() == SpaceReservationStatus.ended) {
            return SpaceReservationResponse.fromEntity(e);
        }
        if (e.getSrSt() == SpaceReservationStatus.cancelled) {
            throw new BusinessException(ErrorCode.SPACE_RESERVATION_CANNOT_CANCEL);
        }

        e.setSrSt(SpaceReservationStatus.ended);
        flushReservationChange();

        // 종료 알림을 원하면 여기서 NotificationType 하나 더 추가해서 보내면 됨

        return SpaceReservationResponse.fromEntity(e);
    }

    @Override
    public SpaceReservationResponse adminCancel(AuthUser me, Integer reservationId, CancelSpaceReservationRequest request) {
        requireOwner(me);
        if (reservationId == null) throw new BusinessException(ErrorCode.BAD_REQUEST);

        SpaceReservationEntity e = spaceReservationRepository.findById(reservationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SPACE_RESERVATION_NOT_FOUND));
        ownership.requireCanManageBuilding(e.getBuilding().getBuildingId());

        if (e.getSrSt() == SpaceReservationStatus.cancelled) {
            return SpaceReservationResponse.fromEntity(e);
        }
        if (e.getSrSt() == SpaceReservationStatus.ended) {
            throw new BusinessException(ErrorCode.SPACE_RESERVATION_CANNOT_CANCEL);
        }

        e.setSrSt(SpaceReservationStatus.cancelled);
        flushReservationChange();

        // =========================
        // 알람 -관리자 취소 직후
        // =========================
        String timeMsg = e.getSrStartAt() + " ~ " + e.getSrEndAt();
        String reason = (request != null && request.getCancelReason() != null && !request.getCancelReason().isBlank())
                ? " 사유: " + request.getCancelReason()
                : "";

        notificationService.notifyUser(
                e.getUser().getUserId(),
                NotificationType.SP_CAN.name(),
                "운영 소유주에 의해 공간 예약이 취소되었습니다. (" + timeMsg + ")" + reason,
                me.getUserId(),
                TargetType.space,
                e.getReservationId(),
                "/space-reservations/my"
        );

        return SpaceReservationResponse.fromEntity(e);
    }

    private boolean isActiveSlotDuplicate(DataIntegrityViolationException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException constraint
                    && constraint.getSQLException().getErrorCode() == 1062) {
                String name = constraint.getConstraintName();
                if (name == null) return false;
                name = name.replace("`", "").replace("\"", "");
                return name.equals("uk_space_reservation_active_slot")
                        || name.equals("space_reservations.uk_space_reservation_active_slot");
            }
            if (cause == cause.getCause()) break;
        }
        return false;
    }

    private void flushReservationChange() {
        try {
            spaceReservationRepository.flush();
        } catch (ObjectOptimisticLockingFailureException failure) {
            if (SpaceReservationEntity.class.getName().equals(failure.getPersistentClassName())) {
                throw new BusinessException(ErrorCode.SPACE_RESERVATION_CONCURRENT_CHANGE);
            }
            throw failure;
        }
    }

    private void requireMember(AuthUser me) {
        if (me == null || me.getUserId() == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (!me.getUserId().equals(ownership.requireMember())) throw new BusinessException(ErrorCode.FORBIDDEN);
    }

    private void requireOwner(AuthUser me) {
        requireMember(me);
        ownership.requireOwner();
    }

    private void notifyOwners(Integer buildingId, String code, String message, String sender,
                              TargetType target, Integer targetId, String url) {
        for (String ownerId : ownership.eligibleOwnerIdsForBuilding(buildingId)) {
            notificationService.notifyUser(ownerId, code, message, sender, target, targetId, url);
        }
    }

}
