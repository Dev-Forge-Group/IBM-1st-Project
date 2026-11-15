// 경로: org/myweb/uniplace/domain/reservation/domain/policy/TourReservationConflictPolicyImpl.java
package org.myweb.uniplace.domain.reservation.domain.policy;

import java.time.LocalDateTime;
import java.util.List;

import org.myweb.uniplace.domain.reservation.domain.enums.TourStatus;
import org.myweb.uniplace.domain.reservation.repository.TourReservationRepository;
import org.myweb.uniplace.global.exception.BusinessException;
import org.myweb.uniplace.global.exception.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.data.domain.PageRequest;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class TourReservationConflictPolicyImpl implements TourReservationConflictPolicy {

    private final TourReservationRepository tourReservationRepository;

    private static final List<TourStatus> INACTIVE = List.of(TourStatus.cancelled, TourStatus.ended);
    private static final PageRequest FIRST_CONFLICT = PageRequest.of(0, 1);

    @Override
    public void validateRoomConflict(Integer roomId, LocalDateTime startAt, LocalDateTime endAt) {
        boolean exists = !tourReservationRepository.findRoomConflictsForUpdate(
                roomId, INACTIVE, startAt, endAt, FIRST_CONFLICT).isEmpty();
        if (exists) {
            throw new BusinessException(ErrorCode.TOUR_RESERVATION_TIME_CONFLICT);
        }
    }

    @Override
    public void validateDuplicateTelTime(String tourTel, LocalDateTime startAt, LocalDateTime endAt) {
        boolean exists = !tourReservationRepository.findTelephoneTimeConflictsForUpdate(
                tourTel, TourTelephone.canonical(tourTel), startAt, endAt, INACTIVE, FIRST_CONFLICT).isEmpty();
        if (exists) {
            throw new BusinessException(ErrorCode.TOUR_RESERVATION_DUPLICATE); // TOUR_RESERVATION_DUPLICATE 등으로 분리 가능
        }
    }
}
