package org.myweb.uniplace.domain.reservation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.myweb.uniplace.domain.notification.application.NotificationService;
import org.myweb.uniplace.domain.notification.domain.enums.TargetType;
import org.myweb.uniplace.domain.property.api.dto.request.SpaceSearchRequest;
import org.myweb.uniplace.domain.property.api.dto.response.SpaceResponse;
import org.myweb.uniplace.domain.property.application.SpaceService;
import org.myweb.uniplace.domain.property.domain.entity.Building;
import org.myweb.uniplace.domain.property.domain.entity.CommonSpace;
import org.myweb.uniplace.domain.property.domain.entity.Room;
import org.myweb.uniplace.domain.property.repository.BuildingRepository;
import org.myweb.uniplace.domain.property.repository.RoomRepository;
import org.myweb.uniplace.domain.property.repository.SpaceRepository;
import org.myweb.uniplace.domain.reservation.api.dto.request.CreateTourReservationRequest;
import org.myweb.uniplace.domain.reservation.api.dto.response.SpaceReservableResponse;
import org.myweb.uniplace.domain.reservation.api.dto.response.TourReservableResponse;
import org.myweb.uniplace.domain.reservation.domain.entity.SpaceReservationEntity;
import org.myweb.uniplace.domain.reservation.domain.entity.TourReservationEntity;
import org.myweb.uniplace.domain.reservation.domain.enums.SpaceReservationStatus;
import org.myweb.uniplace.domain.reservation.domain.enums.TourStatus;
import org.myweb.uniplace.domain.reservation.domain.policy.ReservationValidator;
import org.myweb.uniplace.domain.reservation.domain.policy.SpaceReservationConflictPolicyImpl;
import org.myweb.uniplace.domain.reservation.domain.policy.TourReservationConflictPolicyImpl;
import org.myweb.uniplace.domain.reservation.repository.SpaceReservationRepository;
import org.myweb.uniplace.domain.reservation.repository.TourReservationRepository;
import org.myweb.uniplace.domain.user.domain.entity.User;
import org.myweb.uniplace.domain.user.domain.enums.UserRole;
import org.myweb.uniplace.domain.user.repository.UserRepository;
import org.myweb.uniplace.global.exception.BusinessException;
import org.myweb.uniplace.global.exception.ErrorCode;
import org.myweb.uniplace.global.slack.SlackNotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Availability must use the same strict overlap rule as the public creation paths. */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TourReservationServiceImpl.class, SpaceReservationServiceImpl.class, ReservationValidator.class,
        TourReservationConflictPolicyImpl.class, SpaceReservationConflictPolicyImpl.class})
@Testcontainers
class ReservationAvailabilityIntegrationTest {
    private static final LocalDate DAY = LocalDate.of(2100, 3, 11);
    private static final PageRequest PAGE = PageRequest.of(0, 10);

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("reservation_availability")
            .withUsername("availability_test")
            .withPassword("availability-test-only");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", MYSQL::getJdbcUrl);
        properties.add("spring.datasource.username", MYSQL::getUsername);
        properties.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired BuildingRepository buildings;
    @Autowired RoomRepository rooms;
    @Autowired SpaceRepository spaces;
    @Autowired UserRepository users;
    @Autowired TourReservationRepository tourRows;
    @Autowired SpaceReservationRepository spaceRows;
    @Autowired TourReservationService tours;
    @Autowired SpaceReservationService spaceReservations;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean SpaceService spaceSearch;
    @MockitoBean NotificationService notifications;
    @MockitoBean SlackNotificationService slack;

    private Building building;
    private Room room;
    private Room otherRoom;
    private CommonSpace space;
    private CommonSpace otherSpace;
    private User tenant;

    @BeforeEach
    void fixture() {
        building = buildings.saveAndFlush(Building.builder().buildingNm("Availability fixture")
                .buildingAddr("Test fixture address").build());
        room = room(1);
        otherRoom = room(2);
        space = space("Target space");
        otherSpace = space("Other space");
        String id = "availability-" + building.getBuildingId();
        tenant = users.saveAndFlush(User.builder().userId(id).userNm(id).userEmail(id + "@example.test")
                .userPwd("test-only-hash").userBirth(LocalDate.of(2000, 1, 1)).userTel("010-0000-0000")
                .userNickname(id).userRole(UserRole.tenant).build());
    }

    @Test
    void aPartialTourOverlapIsHiddenWhileInactiveAndOtherRoomRowsStayIsolated() {
        tour(room, at(10), at(10).plusMinutes(30), TourStatus.requested);
        tour(room, at(11), at(12), TourStatus.cancelled);
        tour(room, at(12), at(13), TourStatus.ended);
        tour(otherRoom, at(13), at(14), TourStatus.confirmed);

        assertThat(tourStarts()).containsExactly(at(9), at(11), at(12), at(13), at(14), at(15), at(16), at(17));
        assertThatThrownBy(() -> tours.create(request(at(10), at(11))))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.TOUR_RESERVATION_TIME_CONFLICT));
        verifyNoInteractions(notifications, slack);
    }

    @Test
    void aLegacySpaceOverlapHidesBothSlotsButKeepsTouchingInactiveAndOtherSpaceSlots() {
        spaceReservation(space, at(9), at(11), SpaceReservationStatus.confirmed);
        spaceReservation(space, at(12), at(14), SpaceReservationStatus.cancelled);
        spaceReservation(space, at(14), at(16), SpaceReservationStatus.ended);
        spaceReservation(otherSpace, at(16), at(18), SpaceReservationStatus.requested);
        searchableTargetSpace();

        assertThat(spaceStarts()).containsExactly(at(12), at(14), at(16), at(18));
        verifyNoInteractions(notifications, slack);
    }

    @Test
    void aTourSpanningFromThePreviousDayHidesOnlyOverlappingSlotsOfTheRequestedDay() {
        tour(room, DAY.minusDays(1).atTime(23, 0), at(9).plusMinutes(30), TourStatus.confirmed);
        tour(room, DAY.minusDays(1).atTime(22, 0), DAY.atStartOfDay(), TourStatus.requested);
        tour(room, DAY.plusDays(1).atStartOfDay(), DAY.plusDays(1).atTime(1, 0), TourStatus.requested);

        assertThat(tourStarts()).containsExactly(at(10), at(11), at(12), at(13), at(14), at(15), at(16), at(17));
        verifyNoInteractions(notifications, slack);
    }

    @Test
    void aSpaceReservationSpanningFromThePreviousDayHidesOnlyOverlappingSlotsOfTheRequestedDay() {
        spaceReservation(space, DAY.minusDays(1).atTime(23, 0), at(9), SpaceReservationStatus.requested);
        spaceReservation(space, DAY.minusDays(1).atTime(22, 0), DAY.atStartOfDay(), SpaceReservationStatus.confirmed);
        spaceReservation(space, DAY.plusDays(1).atStartOfDay(), DAY.plusDays(1).atTime(1, 0), SpaceReservationStatus.confirmed);
        searchableTargetSpace();

        assertThat(spaceStarts()).containsExactly(at(10), at(12), at(14), at(16), at(18));
        verifyNoInteractions(notifications, slack);
    }

    @Test
    void aTwentyFiveHourTourCannotPassTheOneHourLocalTimeSlotCheck() {
        assertThatThrownBy(() -> tours.create(request(at(9), DAY.plusDays(1).atTime(10, 0))))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.TOUR_RESERVATION_INVALID_TIME));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM room_reservation WHERE room_id = ?", Integer.class,
                room.getRoomId())).isZero();
        verifyNoInteractions(notifications, slack);
    }

    @Test
    void validSameDayAdjacentToursStillCreateAndDisappearFromAvailability() {
        var first = tours.create(request(at(9), at(10)));
        var second = tours.create(request(at(10), at(11)));

        assertThat(first.getTourSt()).isEqualTo(TourStatus.requested);
        assertThat(first.getTourNm()).isEqualTo("Fixture visitor");
        assertThat(first.getTourTel()).isEqualTo("010-5555-1234");
        assertThat(second.getTourId()).isNotEqualTo(first.getTourId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM room_reservation WHERE room_id = ?", Integer.class,
                room.getRoomId())).isEqualTo(2);
        assertThat(tourStarts()).containsExactly(at(11), at(12), at(13), at(14), at(15), at(16), at(17));
        for (Integer id : List.of(first.getTourId(), second.getTourId())) {
            verify(notifications).notifyAdmins(eq("TOUR_REQ"), any(String.class), isNull(), eq(TargetType.tour),
                    eq(id), eq("/admin/tour-reservations"));
        }
        // The surrounding test transaction has not committed; external alerts are deferred.
        // Committed delivery and rollback behavior are covered by TourReservationConcurrencyIntegrationTest.
        verifyNoInteractions(slack);
        verifyNoMoreInteractions(notifications);
    }

    private List<LocalDateTime> tourStarts() {
        return tours.reservableSlots(building.getBuildingId(), room.getRoomId(), DAY).getAvailableSlots().stream()
                .map(TourReservableResponse.TimeSlotResponse::getStartAt).toList();
    }

    private List<LocalDateTime> spaceStarts() {
        var result = spaceReservations.reservableSpaces(building.getBuildingId(), DAY, PAGE);
        assertThat(result.getContent()).singleElement().satisfies(row -> assertThat(row.getSpaceId()).isEqualTo(space.getSpaceId()));
        return result.getContent().getFirst().getAvailableSlots().stream()
                .map(SpaceReservableResponse.TimeSlotResponse::getStartAt).toList();
    }

    private void searchableTargetSpace() {
        given(spaceSearch.searchPage(any(SpaceSearchRequest.class), eq(PAGE))).willReturn(
                new PageImpl<>(List.of(SpaceResponse.fromEntity(space)), PAGE, 1));
    }

    private TourReservationEntity tour(Room target, LocalDateTime start, LocalDateTime end, TourStatus status) {
        return tourRows.saveAndFlush(TourReservationEntity.builder().building(building).room(target)
                .tourStartAt(start).tourEndAt(end).tourNm("Legacy visitor").tourTel("010-0000-0000")
                .tourPwd("2468").tourSt(status).build());
    }

    private void spaceReservation(CommonSpace target, LocalDateTime start, LocalDateTime end, SpaceReservationStatus status) {
        spaceRows.saveAndFlush(SpaceReservationEntity.builder().building(building).space(target).user(tenant)
                .srStartAt(start).srEndAt(end).srNoPeople(1).srSt(status).build());
    }

    private Room room(int number) {
        return rooms.saveAndFlush(Room.builder().building(building).roomNo(number).floor(1)
                .roomSize(new BigDecimal("20.00")).rentPrice(new BigDecimal("500000")).build());
    }

    private CommonSpace space(String name) {
        return spaces.saveAndFlush(CommonSpace.builder().building(building).spaceNm(name).spaceFloor(1).spaceCapacity(4).build());
    }

    private CreateTourReservationRequest request(LocalDateTime start, LocalDateTime end) {
        return CreateTourReservationRequest.builder().buildingId(building.getBuildingId()).roomId(room.getRoomId())
                .tourStartAt(start).tourEndAt(end).tourNm("Fixture visitor").tourTel("010-5555-1234").tourPwd("2468").build();
    }

    private static LocalDateTime at(int hour) {
        return DAY.atTime(hour, 0);
    }
}
