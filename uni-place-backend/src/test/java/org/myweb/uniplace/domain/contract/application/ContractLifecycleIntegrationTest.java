package org.myweb.uniplace.domain.contract.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.myweb.uniplace.domain.contract.api.dto.request.ContractUpdateRequest;
import org.myweb.uniplace.domain.contract.api.dto.response.ContractResponse;
import org.myweb.uniplace.domain.contract.domain.entity.Contract;
import org.myweb.uniplace.domain.contract.domain.entity.Resident;
import org.myweb.uniplace.domain.contract.domain.enums.ContractStatus;
import org.myweb.uniplace.domain.contract.repository.ContractRepository;
import org.myweb.uniplace.domain.contract.repository.ResidentRepository;
import org.myweb.uniplace.domain.file.application.FileService;
import org.myweb.uniplace.domain.notification.application.NotificationService;
import org.myweb.uniplace.domain.notification.domain.enums.TargetType;
import org.myweb.uniplace.domain.property.domain.entity.Building;
import org.myweb.uniplace.domain.property.domain.entity.Room;
import org.myweb.uniplace.domain.property.domain.enums.RoomStatus;
import org.myweb.uniplace.domain.property.repository.BuildingRepository;
import org.myweb.uniplace.domain.property.repository.RoomRepository;
import org.myweb.uniplace.domain.reservation.api.dto.request.CreateTourReservationRequest;
import org.myweb.uniplace.domain.reservation.application.TourReservationService;
import org.myweb.uniplace.domain.reservation.application.TourReservationServiceImpl;
import org.myweb.uniplace.domain.reservation.domain.policy.ReservationValidator;
import org.myweb.uniplace.domain.reservation.domain.policy.TourReservationConflictPolicyImpl;
import org.myweb.uniplace.domain.user.domain.entity.User;
import org.myweb.uniplace.domain.user.domain.enums.UserRole;
import org.myweb.uniplace.domain.user.repository.UserRepository;
import org.myweb.uniplace.global.slack.SlackNotificationService;
import org.myweb.uniplace.global.exception.BusinessException;
import org.myweb.uniplace.global.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Committed schema, real lifecycle methods and caller transaction visibility around contract handovers. */
@DataJpaTest(showSql = false, properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "logging.level.org.hibernate.SQL=WARN",
        // Disable only automatic scheduling; tests invoke the real scheduled method directly.
        "contract.status-transition-cron=-"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ContractServiceImpl.class, TourReservationServiceImpl.class,
        ReservationValidator.class, TourReservationConflictPolicyImpl.class})
@Testcontainers
class ContractLifecycleIntegrationTest {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("contract_lifecycle")
            .withUsername("contract_lifecycle_test")
            .withPassword("contract-lifecycle-test-only");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", MYSQL::getJdbcUrl);
        properties.add("spring.datasource.username", MYSQL::getUsername);
        properties.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired ContractServiceImpl service;
    @Autowired TourReservationService tours;
    @Autowired ContractRepository contracts;
    @Autowired ResidentRepository residents;
    @Autowired BuildingRepository buildings;
    @Autowired RoomRepository rooms;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean FileService files;
    @MockitoBean ContractImageService images;
    @MockitoBean NotificationService notifications;
    @MockitoBean SlackNotificationService slack;

    private Building building;
    private LocalDate today;
    private int nextRoom;
    private int nextUser;

    @BeforeEach
    void fixture() {
        today = LocalDate.now(KST);
        building = buildings.saveAndFlush(Building.builder().buildingNm("IBM05 lifecycle fixture")
                .buildingAddr("Test fixture address").build());
        clearInvocations(files, images, notifications, slack);
    }

    @Test
    void schedulerHandoverKeepsTheReplacementContractRoomAndResidentsOccupied() {
        Room room = room(RoomStatus.contracted);
        User departing = user(UserRole.tenant);
        User arriving = user(UserRole.user);
        Contract old = contract(departing, room, ContractStatus.active, today.minusMonths(1), today.minusDays(1));
        resident(old);
        Contract next = contract(arriving, room, ContractStatus.approved, today, today.plusMonths(1));

        service.runContractStatusTransition();
        contracts.flush();

        assertThat(roomState(room)).isEqualTo("contracted");
        assertThat(state(old)).isEqualTo("ended");
        assertThat(state(next)).isEqualTo("active");
        assertThat(residentCount(old)).isZero();
        assertThat(residentCount(next)).isEqualTo(1);
        assertThat(role(departing)).isEqualTo("user");
        assertThat(role(arriving)).isEqualTo("tenant");
        assertThat(contractCount(room)).isEqualTo(2);
        assertThatThrownBy(() -> tours.create(CreateTourReservationRequest.builder()
                .buildingId(building.getBuildingId()).roomId(room.getRoomId())
                .tourStartAt(today.plusDays(1).atTime(10, 0)).tourEndAt(today.plusDays(1).atTime(11, 0))
                .tourNm("Lifecycle fixture visitor").tourTel("010-1234-5678").tourPwd("2468").build()))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.TOUR_RESERVATION_ROOM_UNAVAILABLE));
        verifyNoInteractions(files, images, notifications, slack);

        service.runContractStatusTransition();
        contracts.flush();
        assertThat(roomState(room)).isEqualTo("contracted");
        assertThat(residentCount(next)).isEqualTo(1);
        assertThat(contractCount(room)).isEqualTo(2);
        verifyNoInteractions(files, images, notifications, slack);
    }

    @Test
    void sameUserHandoverKeepsTheNewResidentAndTenantRoleAfterTheOldContractEnds() {
        Room room = room(RoomStatus.contracted);
        User tenant = user(UserRole.tenant);
        Contract old = contract(tenant, room, ContractStatus.active, today.minusMonths(1), today.minusDays(1));
        resident(old);
        Contract next = contract(tenant, room, ContractStatus.approved, today, today.plusMonths(1));

        service.runContractStatusTransition();
        contracts.flush();

        assertThat(roomState(room)).isEqualTo("contracted");
        assertThat(state(old)).isEqualTo("ended");
        assertThat(state(next)).isEqualTo("active");
        assertThat(residentCount(old)).isZero();
        assertThat(residentCount(next)).isEqualTo(1);
        assertThat(role(tenant)).isEqualTo("tenant");
        verifyNoInteractions(files, images, notifications, slack);
    }

    @ParameterizedTest
    @EnumSource(value = ContractStatus.class, names = {"ended", "cancelled"})
    void adminDeactivationKeepsAnotherActiveContractInTheSameRoom(ContractStatus target) {
        Room room = room(RoomStatus.contracted);
        User departing = user(UserRole.tenant);
        User remaining = user(UserRole.tenant);
        Contract old = contract(departing, room, ContractStatus.active, today.minusMonths(1), today.plusDays(1));
        Contract other = contract(remaining, room, ContractStatus.active, today, today.plusMonths(1));
        resident(old);
        resident(other);

        var response = update(old, target);
        contracts.flush();

        assertThat(roomState(room)).isEqualTo("contracted");
        assertThat(response.getContractStatus()).isEqualTo(target);
        assertThat(state(old)).isEqualTo(target.name());
        assertThat(state(other)).isEqualTo("active");
        assertThat(residentCount(old)).isZero();
        assertThat(residentCount(other)).isEqualTo(1);
        assertThat(role(departing)).isEqualTo("user");
        assertThat(role(remaining)).isEqualTo("tenant");
        assertThat(contractCount(room)).isEqualTo(2);
        verifyCancellationNotice(departing);
    }

    @Test
    void lastActiveContractReleasesTheRoomAndRoleOnlyOnceWithinTheCallerTransaction() {
        Room room = room(RoomStatus.contracted);
        User tenant = user(UserRole.tenant);
        Contract old = contract(tenant, room, ContractStatus.active, today.minusMonths(1), today.plusDays(1));
        resident(old);

        update(old, ContractStatus.ended);
        contracts.flush();

        assertThat(roomState(room)).isEqualTo("available");
        assertThat(state(old)).isEqualTo("ended");
        assertThat(residentCount(old)).isZero();
        assertThat(role(tenant)).isEqualTo("user");
        assertThat(contractCount(room)).isEqualTo(1);
        verifyCancellationNotice(tenant);

        update(old, ContractStatus.ended);
        contracts.flush();
        assertThat(roomState(room)).isEqualTo("available");
        assertThat(residentCount(old)).isZero();
        assertThat(role(tenant)).isEqualTo("user");
        verifyCancellationNotice(tenant);
    }

    @Test
    void anotherActiveRoomKeepsTheUsersTenantRoleAndItsOwnResidentRecord() {
        User tenant = user(UserRole.tenant);
        Room released = room(RoomStatus.contracted);
        Room occupied = room(RoomStatus.contracted);
        Contract old = contract(tenant, released, ContractStatus.active, today.minusMonths(1), today.plusDays(1));
        Contract other = contract(tenant, occupied, ContractStatus.active, today, today.plusMonths(1));
        resident(old);
        resident(other);

        update(old, ContractStatus.ended);
        contracts.flush();

        assertThat(roomState(released)).isEqualTo("available");
        assertThat(roomState(occupied)).isEqualTo("contracted");
        assertThat(role(tenant)).isEqualTo("tenant");
        assertThat(residentCount(old)).isZero();
        assertThat(residentCount(other)).isEqualTo(1);
        assertThat(state(other)).isEqualTo("active");
        verifyCancellationNotice(tenant);
    }

    @Test
    void futureApprovalAndInactiveHistoryDoNotKeepTheLastActiveRoomOccupied() {
        Room room = room(RoomStatus.contracted);
        User tenant = user(UserRole.tenant);
        Contract old = contract(tenant, room, ContractStatus.active, today.minusMonths(1), today.plusDays(1));
        resident(old);
        Contract future = contract(user(UserRole.user), room, ContractStatus.approved,
                today.plusDays(2), today.plusMonths(2));
        Contract ended = contract(user(UserRole.user), room, ContractStatus.ended,
                today.minusMonths(3), today.minusMonths(2));
        Contract cancelled = contract(user(UserRole.user), room, ContractStatus.cancelled,
                today.minusMonths(2), today.minusMonths(1));

        update(old, ContractStatus.ended);
        contracts.flush();

        assertThat(roomState(room)).isEqualTo("available");
        assertThat(state(future)).isEqualTo("approved");
        assertThat(state(ended)).isEqualTo("ended");
        assertThat(state(cancelled)).isEqualTo("cancelled");
        assertThat(role(tenant)).isEqualTo("user");
        assertThat(residentCount(old)).isZero();
        assertThat(contractCount(room)).isEqualTo(4);
        verifyCancellationNotice(tenant);
    }

    @ParameterizedTest
    @EnumSource(value = RoomStatus.class, names = {"repair", "cleaning", "reserved"})
    void endingAContractPreservesAnExplicitNonContractRoomStatus(RoomStatus preserved) {
        Room room = room(preserved);
        User tenant = user(UserRole.tenant);
        Contract old = contract(tenant, room, ContractStatus.active, today.minusMonths(1), today.plusDays(1));
        resident(old);

        update(old, ContractStatus.ended);
        contracts.flush();

        assertThat(roomState(room)).isEqualTo(preserved.name());
        assertThat(state(old)).isEqualTo("ended");
        assertThat(residentCount(old)).isZero();
        assertThat(role(tenant)).isEqualTo("user");
        verifyCancellationNotice(tenant);
    }

    @Test
    void endingAContractDoesNotReopenASoftDeletedRoom() {
        Room room = room(RoomStatus.contracted);
        room.softDelete();
        rooms.saveAndFlush(room);
        User tenant = user(UserRole.tenant);
        Contract old = contract(tenant, room, ContractStatus.active, today.minusMonths(1), today.plusDays(1));
        resident(old);

        update(old, ContractStatus.ended);
        contracts.flush();

        assertThat(roomState(room)).isEqualTo("contracted");
        assertThat(jdbc.queryForObject("SELECT delete_yn FROM rooms WHERE room_id = ?", String.class, room.getRoomId()))
                .isEqualTo("Y");
        assertThat(state(old)).isEqualTo("ended");
        assertThat(residentCount(old)).isZero();
        assertThat(role(tenant)).isEqualTo("user");
        verifyCancellationNotice(tenant);
    }

    @Test
    void endingAnAdministratorsLastContractDoesNotDemoteTheAdministrator() {
        Room room = room(RoomStatus.contracted);
        User admin = user(UserRole.admin);
        Contract old = contract(admin, room, ContractStatus.active, today.minusMonths(1), today.plusDays(1));
        resident(old);

        update(old, ContractStatus.ended);
        contracts.flush();

        assertThat(roomState(room)).isEqualTo("available");
        assertThat(role(admin)).isEqualTo("admin");
        assertThat(residentCount(old)).isZero();
        verifyCancellationNotice(admin);
    }

    @Test
    void schedulerRetainsFutureApprovalsAndActiveContractsThatHaveNotEnded() {
        Room occupied = room(RoomStatus.contracted);
        User tenant = user(UserRole.tenant);
        Contract current = contract(tenant, occupied, ContractStatus.active, today.minusDays(2), today.plusDays(2));
        resident(current);
        Room futureRoom = room(RoomStatus.available);
        User applicant = user(UserRole.user);
        Contract future = contract(applicant, futureRoom, ContractStatus.approved, today.plusDays(3), today.plusMonths(1));

        service.runContractStatusTransition();
        contracts.flush();

        assertThat(state(current)).isEqualTo("active");
        assertThat(roomState(occupied)).isEqualTo("contracted");
        assertThat(residentCount(current)).isEqualTo(1);
        assertThat(role(tenant)).isEqualTo("tenant");
        assertThat(state(future)).isEqualTo("approved");
        assertThat(roomState(futureRoom)).isEqualTo("available");
        assertThat(residentCount(future)).isZero();
        assertThat(role(applicant)).isEqualTo("user");
        verifyNoInteractions(files, images, notifications, slack);
    }

    private ContractResponse update(Contract contract, ContractStatus status) {
        return service.updateContractForAdmin(contract.getContractId(),
                ContractUpdateRequest.builder().contractStatus(status).build());
    }

    private void verifyCancellationNotice(User user) {
        verify(notifications, times(1)).notifyUser(eq(user.getUserId()), eq("CONTRACT_CAN"), anyString(), isNull(),
                eq(TargetType.contract), isNull(), eq("/me?tab=myroom&sub=contracts"));
        verifyNoMoreInteractions(notifications);
        verifyNoInteractions(files, images, slack);
    }

    private User user(UserRole role) {
        String id = "ibm05-" + building.getBuildingId() + "-" + ++nextUser;
        return users.saveAndFlush(User.builder().userId(id).userNm("Lifecycle fixture")
                .userEmail(id + "@example.test").userPwd("test-only-hash")
                .userBirth(LocalDate.of(2000, 1, 1)).userTel("010-0000-0000")
                .userNickname(id).userRole(role).build());
    }

    private Room room(RoomStatus status) {
        return rooms.saveAndFlush(Room.builder().building(building).roomNo(++nextRoom).floor(1)
                .roomSize(new BigDecimal("20.00")).rentPrice(new BigDecimal("500000"))
                .roomSt(status).build());
    }

    private Contract contract(User user, Room room, ContractStatus status, LocalDate start, LocalDate end) {
        return contracts.saveAndFlush(Contract.builder().user(user).room(room).contractSt(status)
                .contractStart(start).contractEnd(end).rentPrice(new BigDecimal("500000"))
                .lessorNm("Fixture lessor").lessorTel("010-1111-1111").lessorAddr("Fixture address")
                .lessorRrn("test-only").build());
    }

    private void resident(Contract contract) {
        residents.saveAndFlush(Resident.builder().buildingId(building.getBuildingId())
                .contractId(contract.getContractId()).userId(contract.getUser().getUserId()).build());
    }

    private String state(Contract contract) {
        return jdbc.queryForObject("SELECT contract_st FROM contract WHERE contract_id = ?", String.class,
                contract.getContractId());
    }

    private String roomState(Room room) {
        return jdbc.queryForObject("SELECT room_st FROM rooms WHERE room_id = ?", String.class, room.getRoomId());
    }

    private String role(User user) {
        return jdbc.queryForObject("SELECT user_role FROM users WHERE user_id = ?", String.class, user.getUserId());
    }

    private int residentCount(Contract contract) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM residents WHERE contract_id = ? AND user_id = ?", Integer.class,
                contract.getContractId(), contract.getUser().getUserId());
    }

    private int contractCount(Room room) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM contract WHERE room_id = ?", Integer.class, room.getRoomId());
    }
}
