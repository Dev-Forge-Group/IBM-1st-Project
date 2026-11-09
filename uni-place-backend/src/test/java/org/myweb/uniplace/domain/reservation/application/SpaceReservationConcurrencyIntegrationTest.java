package org.myweb.uniplace.domain.reservation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.myweb.uniplace.domain.contract.domain.entity.Contract;
import org.myweb.uniplace.domain.contract.domain.enums.ContractStatus;
import org.myweb.uniplace.domain.contract.repository.ContractRepository;
import org.myweb.uniplace.domain.notification.application.NotificationService;
import org.myweb.uniplace.domain.notification.domain.enums.TargetType;
import org.myweb.uniplace.domain.property.application.SpaceService;
import org.myweb.uniplace.domain.property.domain.entity.Building;
import org.myweb.uniplace.domain.property.domain.entity.CommonSpace;
import org.myweb.uniplace.domain.property.domain.entity.Room;
import org.myweb.uniplace.domain.property.repository.BuildingRepository;
import org.myweb.uniplace.domain.property.repository.RoomRepository;
import org.myweb.uniplace.domain.property.repository.SpaceRepository;
import org.myweb.uniplace.domain.reservation.api.dto.request.CreateSpaceReservationRequest;
import org.myweb.uniplace.domain.reservation.api.dto.response.SpaceReservationResponse;
import org.myweb.uniplace.domain.reservation.domain.entity.SpaceReservationEntity;
import org.myweb.uniplace.domain.reservation.domain.enums.SpaceReservationStatus;
import org.myweb.uniplace.domain.reservation.domain.policy.ReservationValidator;
import org.myweb.uniplace.domain.reservation.domain.policy.SpaceReservationConflictPolicyImpl;
import org.myweb.uniplace.domain.reservation.repository.SpaceReservationRepository;
import org.myweb.uniplace.domain.user.domain.entity.User;
import org.myweb.uniplace.domain.user.domain.enums.UserRole;
import org.myweb.uniplace.domain.user.domain.enums.UserStatus;
import org.myweb.uniplace.domain.user.repository.UserRepository;
import org.myweb.uniplace.global.exception.BusinessException;
import org.myweb.uniplace.global.exception.ErrorCode;
import org.myweb.uniplace.global.security.AuthUser;
import org.myweb.uniplace.global.slack.SlackNotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Two real caller transactions compete after opening their own repeatable-read snapshots. */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({SpaceReservationServiceImpl.class, ReservationValidator.class, SpaceReservationConflictPolicyImpl.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class SpaceReservationConcurrencyIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("space_reservation_concurrency")
            .withUsername("space_concurrency_test")
            .withPassword("space-concurrency-test-only");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", MYSQL::getJdbcUrl);
        properties.add("spring.datasource.username", MYSQL::getUsername);
        properties.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired BuildingRepository buildings;
    @Autowired RoomRepository rooms;
    @Autowired ContractRepository contracts;
    @Autowired SpaceRepository spaces;
    @Autowired SpaceReservationRepository reservations;
    @Autowired SpaceReservationService service;
    @MockitoBean NotificationService notifications;
    @MockitoBean SlackNotificationService slack;
    @MockitoBean SpaceService spaceSearch;

    @Test
    void onlyOneCallerCanCommitTheSameEmptySpaceSlotAfterBothSnapshotsHaveOpened() throws Exception {
        Fixture fixture = committedFixture();
        assertThat(jdbc.queryForList("""
                SELECT COLUMN_NAME FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'space_reservations'
                   AND INDEX_NAME = 'uk_space_reservation_active_slot' AND NON_UNIQUE = 0
                 ORDER BY SEQ_IN_INDEX
                """, String.class)).containsExactly("space_id", "sr_start_at", "sr_end_at", "active_slot");
        assertThat(jdbc.queryForObject("""
                SELECT EXTRA FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'space_reservations' AND COLUMN_NAME = 'active_slot'
                """, String.class)).contains("STORED GENERATED");
        List<Outcome> outcomes = race(fixture.firstUser(), request(fixture, fixture.spaceId(), fixture.start()),
                fixture.secondUser(), request(fixture, fixture.spaceId(), fixture.start()));

        long successful = outcomes.stream().filter(result -> result.failure() == null).count();
        int stored = countAt(fixture.spaceId(), fixture.start());
        System.out.printf("SPACE_CONCURRENCY successful=%d stored=%d outcomes=%s%n", successful, stored, outcomes);
        assertThat(successful).as("same slot must have one committed winner; stored=%s, outcomes=%s", stored, outcomes)
                .isEqualTo(1);
        assertThat(stored).isEqualTo(1);
        assertThat(outcomes.stream().filter(result -> result.failure() != null).toList()).singleElement()
                .satisfies(result -> assertFailure(result.failure(), ErrorCode.SPACE_RESERVATION_TIME_CONFLICT));
        Integer winner = outcomes.stream().filter(result -> result.failure() == null).findFirst().orElseThrow().reservationId();
        verifyCreationEvents(winner);
    }

    @Test
    void callersForDifferentEmptySpacesCanBothCommitWithoutADeadlock() throws Exception {
        Fixture fixture = committedFixture();
        List<Outcome> outcomes = race(fixture.firstUser(), request(fixture, fixture.spaceId(), fixture.start()),
                fixture.secondUser(), request(fixture, fixture.secondSpaceId(), fixture.start()));

        assertThat(outcomes).allSatisfy(result -> assertThat(result.failure()).isNull());
        assertThat(countAt(fixture.spaceId(), fixture.start())).isEqualTo(1);
        assertThat(countAt(fixture.secondSpaceId(), fixture.start())).isEqualTo(1);
    }

    @Test
    void callersForAdjacentFixedSlotsCanBothCommit() throws Exception {
        Fixture fixture = committedFixture();
        List<Outcome> outcomes = race(fixture.firstUser(), request(fixture, fixture.spaceId(), fixture.start()),
                fixture.secondUser(), request(fixture, fixture.spaceId(), fixture.start().plusHours(2)));

        assertThat(outcomes).allSatisfy(result -> assertThat(result.failure()).isNull());
        assertThat(countAt(fixture.spaceId(), fixture.start())).isEqualTo(1);
        assertThat(countAt(fixture.spaceId(), fixture.start().plusHours(2))).isEqualTo(1);
    }

    @Test
    void aLegacyNineToElevenReservationStillConflictsWithBothOverlappingFixedSlots() {
        Fixture fixture = committedFixture();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            reservations.saveAndFlush(SpaceReservationEntity.builder()
                    .building(buildings.findById(fixture.buildingId()).orElseThrow())
                    .space(spaces.findById(fixture.spaceId()).orElseThrow())
                    .user(users.findById(fixture.firstUser()).orElseThrow())
                    .srStartAt(fixture.start().minusHours(1)).srEndAt(fixture.start().plusHours(1))
                    .srNoPeople(1).srSt(SpaceReservationStatus.requested).build());
        });

        for (LocalDateTime start : List.of(fixture.start().minusHours(2), fixture.start())) {
            assertThatThrownBy(() -> service.create(tenant(fixture.secondUser()), request(fixture, fixture.spaceId(), start)))
                    .satisfies(failure -> assertFailure(failure, ErrorCode.SPACE_RESERVATION_TIME_CONFLICT));
        }

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM space_reservations WHERE space_id = ?", Integer.class,
                fixture.spaceId())).isEqualTo(1);
        verifyNoInteractions(notifications, slack);
    }

    @Test
    void cancellingAndEndingReservationsReleaseTheSlotWithoutDeletingTheirHistory() {
        Fixture fixture = committedFixture();
        var first = service.create(tenant(fixture.firstUser()), request(fixture, fixture.spaceId(), fixture.start()));
        service.cancel(tenant(fixture.firstUser()), first.getReservationId());
        var second = service.create(tenant(fixture.secondUser()), request(fixture, fixture.spaceId(), fixture.start()));
        service.adminEnd(new AuthUser(fixture.adminUser(), "ADMIN"), second.getReservationId());
        clearInvocations(notifications, slack);

        var third = service.create(tenant(fixture.firstUser()), request(fixture, fixture.spaceId(), fixture.start()));

        assertThat(jdbc.queryForList("SELECT sr_st FROM space_reservations WHERE space_id = ? ORDER BY reservation_id",
                String.class, fixture.spaceId())).containsExactly("cancelled", "ended", "requested");
        assertThat(jdbc.queryForObject("SELECT row_version FROM space_reservations WHERE reservation_id = ?", Long.class,
                first.getReservationId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT row_version FROM space_reservations WHERE reservation_id = ?", Long.class,
                second.getReservationId())).isEqualTo(1L);
        assertThat(countAt(fixture.spaceId(), fixture.start())).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM space_reservations WHERE space_id = ? AND active_slot = 1
                """, Integer.class, fixture.spaceId())).isEqualTo(1);
        verifyCreationEvents(third.getReservationId());
    }

    @Test
    void aStaleAdminConfirmationCannotRestoreACancelledReservationAfterTheSlotWasReused() throws Exception {
        Fixture fixture = committedFixture();
        var old = service.create(tenant(fixture.firstUser()), request(fixture, fixture.spaceId(), fixture.start()));
        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        var worker = Executors.newSingleThreadExecutor();
        try {
            var stale = worker.submit(() -> {
                try {
                    SpaceReservationResponse result = new TransactionTemplate(transactions).execute(status -> {
                        assertThat(reservations.findById(old.getReservationId()).orElseThrow().getSrSt())
                                .isEqualTo(SpaceReservationStatus.requested);
                        loaded.countDown();
                        await(resume);
                        return service.adminConfirm(new AuthUser(fixture.adminUser(), "ADMIN"), old.getReservationId());
                    });
                    return new Outcome(result.getReservationId(), null);
                } catch (Throwable failure) {
                    return new Outcome(null, failure);
                }
            });
            assertThat(loaded.await(15, TimeUnit.SECONDS)).isTrue();
            service.cancel(tenant(fixture.firstUser()), old.getReservationId());
            var replacement = service.create(tenant(fixture.secondUser()), request(fixture, fixture.spaceId(), fixture.start()));
            clearInvocations(notifications, slack);
            resume.countDown();

            assertFailure(stale.get(30, TimeUnit.SECONDS).failure(), ErrorCode.SPACE_RESERVATION_CONCURRENT_CHANGE);
            assertThat(jdbc.queryForList("SELECT sr_st FROM space_reservations WHERE space_id = ? ORDER BY reservation_id",
                    String.class, fixture.spaceId())).containsExactly("cancelled", "requested");
            assertThat(jdbc.queryForObject("SELECT sr_st FROM space_reservations WHERE reservation_id = ?", String.class,
                    replacement.getReservationId())).isEqualTo("requested");
            verifyNoInteractions(notifications, slack);
        } finally {
            resume.countDown();
            worker.shutdownNow();
            assertThat(worker.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private List<Outcome> race(String firstUser, CreateSpaceReservationRequest firstRequest,
                               String secondUser, CreateSpaceReservationRequest secondRequest) throws Exception {
        CyclicBarrier snapshotsOpened = new CyclicBarrier(2);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> createAfterOpeningSnapshot(firstUser, firstRequest, snapshotsOpened));
            var second = workers.submit(() -> createAfterOpeningSnapshot(secondUser, secondRequest, snapshotsOpened));
            return List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        } finally {
            workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

    }

    private int countAt(Integer spaceId, LocalDateTime start) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM space_reservations
                 WHERE space_id = ? AND sr_start_at = ? AND sr_end_at = ?
                """, Integer.class, spaceId, start, start.plusHours(2));
    }

    private Outcome createAfterOpeningSnapshot(String userId, CreateSpaceReservationRequest request, CyclicBarrier snapshotsOpened) {
        try {
            SpaceReservationResponse response = new TransactionTemplate(transactions).execute(status -> {
                // This is the same ordinary User read performed before conflict validation in create().
                assertThat(users.findById(userId)).isPresent();
                String isolation = jdbc.queryForObject("SELECT @@session.transaction_isolation", String.class);
                assertThat(isolation).isEqualTo("REPEATABLE-READ");
                System.out.printf("SPACE_CONCURRENCY worker=%s connection=%s isolation=%s%n", userId,
                        jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class), isolation);
                try {
                    snapshotsOpened.await(15, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    throw new IllegalStateException("both caller snapshots must be ready", failure);
                }
                return service.create(tenant(userId), request);
            });
            return new Outcome(response.getReservationId(), null);
        } catch (Throwable failure) {
            return new Outcome(null, failure);
        }
    }

    private Fixture fixture() {
        Building building = buildings.saveAndFlush(Building.builder().buildingNm("Concurrent reservation fixture")
                .buildingAddr("Test fixture address").build());
        CommonSpace space = spaces.saveAndFlush(CommonSpace.builder().building(building).spaceNm("Concurrency room")
                .spaceFloor(1).spaceCapacity(4).build());
        CommonSpace secondSpace = spaces.saveAndFlush(CommonSpace.builder().building(building).spaceNm("Independent room")
                .spaceFloor(1).spaceCapacity(4).build());
        String prefix = "space-race-" + building.getBuildingId() + "-";
        for (int i = 1; i <= 2; i++) {
            User user = user(prefix + i, UserRole.tenant);
            Room room = rooms.saveAndFlush(Room.builder().building(building).roomNo(i).floor(1)
                    .roomSize(new BigDecimal("20.00")).rentPrice(new BigDecimal("500000")).build());
            contracts.saveAndFlush(Contract.builder().user(user).room(room).contractSt(ContractStatus.active)
                    .contractStart(LocalDate.of(2100, 1, 1)).contractEnd(LocalDate.of(2100, 7, 1))
                    .rentPrice(new BigDecimal("500000")).lessorNm("Test lessor").lessorTel("010-1111-1111")
                    .lessorAddr("Fixture address").lessorRrn("test-only").build());
        }
        user(prefix + "admin", UserRole.admin);
        LocalDateTime start = LocalDate.now().plusDays(2).atTime(10, 0);
        return new Fixture(building.getBuildingId(), space.getSpaceId(), secondSpace.getSpaceId(), prefix + "1", prefix + "2",
                prefix + "admin", start);
    }

    private Fixture committedFixture() {
        return new TransactionTemplate(transactions).execute(status -> fixture());
    }

    private User user(String id, UserRole role) {
        return users.saveAndFlush(User.builder().userId(id).userNm(id).userEmail(id + "@example.test")
                .userPwd("test-only-hash").userBirth(LocalDate.of(2000, 1, 1)).userTel("010-0000-0000")
                .userNickname(id).userRole(role).userSt(UserStatus.active).deleteYN("N").build());
    }

    private static CreateSpaceReservationRequest request(Fixture fixture, Integer spaceId, LocalDateTime start) {
        return CreateSpaceReservationRequest.builder().buildingId(fixture.buildingId()).spaceId(spaceId)
                .srStartAt(start).srEndAt(start.plusHours(2)).srNoPeople(1).build();
    }

    private static AuthUser tenant(String userId) {
        return new AuthUser(userId, "TENANT");
    }

    private void verifyCreationEvents(Integer reservationId) {
        String winnerUser = jdbc.queryForObject("SELECT user_id FROM space_reservations WHERE reservation_id = ?",
                String.class, reservationId);
        verify(notifications).notifyUser(eq(winnerUser), eq("SP_REQ"), anyString(), isNull(),
                eq(TargetType.space), eq(reservationId), eq("/space-reservations/my"));
        verify(notifications).notifyAdmins(eq("SP_REQ"), anyString(), eq(winnerUser), eq(TargetType.space),
                eq(reservationId), eq("/admin/space-reservations"));
        verify(slack).sendSpaceReservationAlert(eq(reservationId), eq(winnerUser), anyString(), anyString());
        verifyNoMoreInteractions(notifications, slack);
    }

    private static void assertFailure(Throwable failure, ErrorCode expected) {
        assertThat(failure).isInstanceOfSatisfying(BusinessException.class,
                business -> assertThat(business.getErrorCode()).isEqualTo(expected));
    }

    private static void await(CountDownLatch signal) {
        try {
            assertThat(signal.await(15, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for a caller transaction", failure);
        }
    }

    private record Fixture(Integer buildingId, Integer spaceId, Integer secondSpaceId, String firstUser,
                           String secondUser, String adminUser, LocalDateTime start) {}

    private record Outcome(Integer reservationId, Throwable failure) {}
}
