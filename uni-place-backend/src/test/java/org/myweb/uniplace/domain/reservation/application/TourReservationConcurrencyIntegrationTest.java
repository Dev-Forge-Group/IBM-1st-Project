package org.myweb.uniplace.domain.reservation.application;

import static org.myweb.uniplace.support.TestOwnershipFixtures.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.myweb.uniplace.domain.notification.application.NotificationService;
import org.myweb.uniplace.domain.property.domain.entity.Building;
import org.myweb.uniplace.domain.property.domain.entity.Room;
import org.myweb.uniplace.domain.property.repository.BuildingRepository;
import org.myweb.uniplace.domain.property.repository.RoomRepository;
import org.myweb.uniplace.domain.reservation.api.dto.request.CancelTourReservationRequest;
import org.myweb.uniplace.domain.reservation.api.dto.request.CreateTourReservationRequest;
import org.myweb.uniplace.domain.reservation.domain.entity.TourReservationEntity;
import org.myweb.uniplace.domain.reservation.domain.enums.TourStatus;
import org.myweb.uniplace.domain.reservation.repository.TourReservationRepository;
import org.myweb.uniplace.global.exception.BusinessException;
import org.myweb.uniplace.global.security.AuthUser;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.myweb.uniplace.global.exception.ErrorCode;
import org.myweb.uniplace.global.slack.SlackNotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Competing real caller transactions start with repeatable-read snapshots, including stale managed rows. */
@SpringBootTest(properties = {
        "jwt.secret=tour-concurrency-test-secret-with-at-least-32-bytes",
        "jwt.access-exp=3600000",
        "jwt.refresh-exp=86400000",
        "spring.mail.host=localhost",
        "spring.mail.port=2525",
        "spring.mail.username=local",
        "spring.mail.password=local",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.show-sql=false",
        "logging.level.org.hibernate.SQL=WARN",
        "payment.reconcile.enabled=false",
        "ai.fastapi.base-url=http://127.0.0.1:9",
        "slack.webhook-url="
})
@Testcontainers
class TourReservationConcurrencyIntegrationTest {
    private static final String PIN = "2468";
    private static final LocalDateTime START = LocalDate.of(2100, 3, 12).atTime(10, 0);

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("tour_reservation_concurrency")
            .withUsername("tour_concurrency_test")
            .withPassword("tour-concurrency-test-only");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", MYSQL::getJdbcUrl);
        properties.add("spring.datasource.username", MYSQL::getUsername);
        properties.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired BuildingRepository buildings;
    @Autowired RoomRepository rooms;
    @Autowired TourReservationRepository rows;
    @Autowired TourReservationService tours;
    @MockitoBean NotificationService notifications;
    @MockitoBean SlackNotificationService slack;

    private Fixture f;

    @BeforeEach
    void committedFixture() {
        f = new TransactionTemplate(transactions).execute(status -> {
            Building building = buildings.saveAndFlush(Building.builder().buildingNm("IBM03 concurrency fixture")
                    .buildingAddr("Test fixture address").build());
            rentalOperator(jdbc, "tour-race-owner-" + building.getBuildingId(), building.getBuildingId());
            return new Fixture(building.getBuildingId(), room(building, 1).getRoomId(), room(building, 2).getRoomId());
        });
        clearInvocations(notifications, slack);
    }

    @Test
    void sameEmptyRoomHasOnlyOneCommittedCreatorEvenAfterBothCallerSnapshotsOpened() throws Exception {
        List<Outcome> results = race(() -> create(f.room(), phone(1), START),
                () -> create(f.room(), phone(2), START));

        oneWinner(results, ErrorCode.TOUR_RESERVATION_TIME_CONFLICT);
        assertThat(activeInRoom(f.room(), START, START.plusHours(1))).isEqualTo(1);
        assertThat(rowCount()).isEqualTo(1);
        assertCreationEvents(1);
    }

    @Test
    void equivalentTelephonesAcrossDifferentRoomsHaveOnlyOneCommittedCreator() throws Exception {
        List<Outcome> results = race(() -> create(f.room(), phone(1), START),
                () -> create(f.otherRoom(), hyphens(phone(1)), START));

        oneWinner(results, ErrorCode.TOUR_RESERVATION_DUPLICATE);
        assertThat(activeCount()).isEqualTo(1);
        assertThat(rowCount()).isEqualTo(1);
        assertCreationEvents(1);
    }

    @Test
    void creationAndRestorationCannotBothOccupyAnOverlappingLegacyHalfHour() throws Exception {
        Integer history = history(f.room(), phone(2), START.plusMinutes(15), START.plusMinutes(45), TourStatus.cancelled);

        List<Outcome> results = race(() -> create(f.room(), phone(1), START), () -> restore(history));

        oneWinner(results, ErrorCode.TOUR_RESERVATION_TIME_CONFLICT);
        assertThat(activeInRoom(f.room(), START, START.plusHours(1))).isEqualTo(1);
        if (results.get(0).failure() == null) {
            assertThat(state(history)).isEqualTo("cancelled");
            assertThat(rowCount()).isEqualTo(2);
            assertCreationEvents(1);
        } else {
            assertThat(state(history)).isEqualTo("confirmed");
            assertThat(rowCount()).isEqualTo(1);
            assertCreationEvents(0);
        }
    }

    @Test
    void twoRestorationsCannotBothOccupyOverlappingNonstandardHistory() throws Exception {
        Integer first = history(f.room(), phone(1), START, START.plusMinutes(30), TourStatus.cancelled);
        Integer second = history(f.room(), phone(2), START.plusMinutes(15), START.plusHours(1), TourStatus.ended);

        oneWinner(race(() -> restore(first), () -> restore(second)), ErrorCode.TOUR_RESERVATION_TIME_CONFLICT);

        assertThat(activeInRoom(f.room(), START, START.plusHours(1))).isEqualTo(1);
        assertThat(rowCount()).isEqualTo(2);
        assertCreationEvents(0);
    }

    @Test
    void twoRestorationsAcrossRoomsStillShareTheTelephoneAliasConflict() throws Exception {
        Integer first = history(f.room(), phone(1), START, START.plusHours(1), TourStatus.cancelled);
        Integer second = history(f.otherRoom(), hyphens(phone(1)), START, START.plusHours(1), TourStatus.ended);

        oneWinner(race(() -> restore(first), () -> restore(second)), ErrorCode.TOUR_RESERVATION_DUPLICATE);

        assertThat(activeCount()).isEqualTo(1);
        assertThat(rowCount()).isEqualTo(2);
        assertCreationEvents(0);
    }

    @Test
    void independentRoomsAndTelephonesCanBothCommitWithoutADeadlock() throws Exception {
        List<Outcome> results = race(() -> create(f.room(), phone(1), START),
                () -> create(f.otherRoom(), phone(2), START));

        assertThat(results).allSatisfy(result -> assertThat(result.failure()).isNull());
        assertThat(activeCount()).isEqualTo(2);
        assertCreationEvents(2);
    }

    @Test
    void adjacentIntervalsInTheSameRoomAndTelephoneCanBothCommit() throws Exception {
        List<Outcome> results = race(() -> create(f.room(), phone(1), START),
                () -> create(f.room(), hyphens(phone(1)), START.plusHours(1)));

        assertThat(results).allSatisfy(result -> assertThat(result.failure()).isNull());
        assertThat(activeInRoom(f.room(), START, START.plusHours(1))).isEqualTo(1);
        assertThat(activeInRoom(f.room(), START.plusHours(1), START.plusHours(2))).isEqualTo(1);
        assertCreationEvents(2);
    }

    @Test
    void inactiveHistoryCanBeReusedWithoutDeletingItOrRejectingAdjacentLegacyHistory() {
        Integer cancelled = history(f.room(), phone(1), START, START.plusHours(1), TourStatus.cancelled);
        Integer ended = history(f.room(), phone(1), START.minusMinutes(15), START.plusMinutes(30), TourStatus.ended);
        Integer adjacent = history(f.room(), phone(1), START.minusMinutes(30), START, TourStatus.confirmed);

        Integer current = create(f.room(), hyphens(phone(1)), START);

        assertThat(state(cancelled)).isEqualTo("cancelled");
        assertThat(state(ended)).isEqualTo("ended");
        assertThat(state(adjacent)).isEqualTo("confirmed");
        assertThat(state(current)).isEqualTo("requested");
        assertThat(activeInRoom(f.room(), START, START.plusHours(1))).isEqualTo(1);
        assertThat(rowCount()).isEqualTo(4);
        assertCreationEvents(1);
    }

    @Test
    void aStaleManagedConfirmationRechecksRestorationAfterCancellationAndSlotReuse() throws Exception {
        Integer old = create(f.room(), phone(1), START);
        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        var worker = Executors.newSingleThreadExecutor();
        try {
            var stale = worker.submit(() -> staleMutation(old, loaded, resume, () -> restore(old)));
            await(loaded);
            tours.cancel(old, cancelRequest(phone(1)));
            Integer replacement = create(f.room(), phone(2), START);
            clearInvocations(notifications, slack);
            resume.countDown();

            assertFailure(stale.get(45, TimeUnit.SECONDS).failure(), ErrorCode.TOUR_RESERVATION_TIME_CONFLICT);
            assertThat(state(old)).isEqualTo("cancelled");
            assertThat(state(replacement)).isEqualTo("requested");
            assertThat(activeInRoom(f.room(), START, START.plusHours(1))).isEqualTo(1);
            assertCreationEvents(0);
            assertThat(notificationCount("TOUR_CAN")).isZero();
        } finally {
            resume.countDown();
            worker.shutdownNow();
            assertThat(worker.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void aStaleCancellationCannotOverwriteACommittedEndedStateOrNotifyAdmins() throws Exception {
        Integer old = create(f.room(), phone(1), START);
        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        var worker = Executors.newSingleThreadExecutor();
        try {
            var stale = worker.submit(() -> staleMutation(old, loaded, resume,
                    () -> tours.cancel(old, cancelRequest(hyphens(phone(1)))).getTourId()));
            await(loaded);
            changeStatusOwned(old, TourStatus.ended);
            clearInvocations(notifications, slack);
            resume.countDown();

            assertFailure(stale.get(45, TimeUnit.SECONDS).failure(), ErrorCode.TOUR_RESERVATION_CANNOT_CANCEL);
            assertThat(state(old)).isEqualTo("ended");
            assertCreationEvents(0);
            assertThat(notificationCount("TOUR_CAN")).isZero();
        } finally {
            resume.countDown();
            worker.shutdownNow();
            assertThat(worker.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void concurrentCancellationsRemainIdempotentAndProduceOnlyOneCancellationEvent() throws Exception {
        Integer old = create(f.room(), phone(1), START);
        clearInvocations(notifications, slack);

        List<Outcome> results = race(() -> {
            rows.findById(old).orElseThrow();
            return tours.cancel(old, cancelRequest(phone(1))).getTourId();
        }, () -> {
            rows.findById(old).orElseThrow();
            return tours.cancel(old, cancelRequest(hyphens(phone(1)))).getTourId();
        });

        assertThat(results).allSatisfy(result -> {
            assertThat(result.failure()).isNull();
            assertThat(result.id()).isEqualTo(old);
        });
        assertThat(state(old)).isEqualTo("cancelled");
        assertThat(activeCount()).isZero();
        assertThat(rowCount()).isEqualTo(1);
        assertThat(notificationCount("TOUR_CAN")).isEqualTo(1);
        assertCreationEvents(0);
    }

    @Test
    void aRolledBackCallerDoesNotSendASlackAlertForAnUncommittedCreation() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            create(f.room(), phone(1), START);
            status.setRollbackOnly();
        });

        assertThat(rowCount()).isZero();
        assertSlackEvents(0);
    }

    @Test
    void aSlowAfterCommitSlackAlertDoesNotKeepAnotherCreatorWaitingOnTheGuard() throws Exception {
        CountDownLatch alertEntered = new CountDownLatch(1);
        CountDownLatch releaseAlert = new CountDownLatch(1);
        AtomicBoolean firstAlert = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (firstAlert.compareAndSet(true, false)) {
                alertEntered.countDown();
                await(releaseAlert);
            }
            return null;
        }).when(slack).sendTourReservationAlert(any(), anyString(), anyString(), anyString());
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> create(f.room(), phone(1), START));
            await(alertEntered);
            assertThat(rowCount()).as("Slack must run only after the first row is committed").isEqualTo(1);
            var second = workers.submit(() -> create(f.otherRoom(), phone(2), START));

            assertThat(second.get(10, TimeUnit.SECONDS)).isNotNull();
            assertThat(rowCount()).isEqualTo(2);
            releaseAlert.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS)).isNotNull();
            assertCreationEvents(2);
        } finally {
            releaseAlert.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void aSlackFailureAfterCommitDoesNotTurnAPersistedCreationIntoAnError() {
        doThrow(new IllegalStateException("Synthetic Slack delivery failure"))
                .when(slack).sendTourReservationAlert(any(), anyString(), anyString(), anyString());

        assertThatCode(() -> create(f.room(), phone(1), START)).doesNotThrowAnyException();

        assertThat(rowCount()).isEqualTo(1);
        assertThat(activeCount()).isEqualTo(1);
        assertCreationEvents(1);
    }

    private List<Outcome> race(Supplier<Integer> first, Supplier<Integer> second) throws Exception {
        CyclicBarrier snapshotsOpened = new CyclicBarrier(2);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var a = workers.submit(() -> afterSnapshot(first, snapshotsOpened));
            var b = workers.submit(() -> afterSnapshot(second, snapshotsOpened));
            return List.of(a.get(45, TimeUnit.SECONDS), b.get(45, TimeUnit.SECONDS));
        } finally {
            workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Outcome afterSnapshot(Supplier<Integer> action, CyclicBarrier snapshotsOpened) {
        return execute(() -> {
            openRepeatableReadSnapshot();
            try {
                snapshotsOpened.await(15, TimeUnit.SECONDS);
            } catch (Exception failure) {
                throw new IllegalStateException("both caller snapshots must open before writes", failure);
            }
            return action.get();
        });
    }

    private Outcome staleMutation(Integer id, CountDownLatch loaded, CountDownLatch resume, Supplier<Integer> action) {
        return execute(() -> {
            assertThat(rows.findById(id).orElseThrow().getTourSt()).isEqualTo(TourStatus.requested);
            openRepeatableReadSnapshot();
            loaded.countDown();
            await(resume);
            return action.get();
        });
    }

    private Outcome execute(Supplier<Integer> action) {
        try {
            return new Outcome(new TransactionTemplate(transactions).execute(status -> action.get()), null);
        } catch (Throwable failure) {
            return new Outcome(null, failure);
        }
    }

    private void openRepeatableReadSnapshot() {
        assertThat(jdbc.queryForObject("SELECT @@session.transaction_isolation", String.class)).isEqualTo("REPEATABLE-READ");
        jdbc.queryForObject("SELECT COUNT(*) FROM room_reservation WHERE building_id = ?", Integer.class, f.building());
    }

    private Integer create(Integer room, String phone, LocalDateTime start) {
        return tours.create(CreateTourReservationRequest.builder().buildingId(f.building()).roomId(room)
                .tourStartAt(start).tourEndAt(start.plusHours(1)).tourNm("IBM03 fixture visitor")
                .tourTel(phone).tourPwd(PIN).build()).getTourId();
    }

    private Integer restore(Integer id) {
        changeStatusOwned(id, TourStatus.confirmed);
        return id;
    }

    private Integer history(Integer room, String phone, LocalDateTime start, LocalDateTime end, TourStatus state) {
        return new TransactionTemplate(transactions).execute(status -> rows.saveAndFlush(TourReservationEntity.builder()
                .building(buildings.findById(f.building()).orElseThrow()).room(rooms.findById(room).orElseThrow())
                .tourNm("Legacy concurrency fixture").tourTel(phone).tourPwd(PIN)
                .tourStartAt(start).tourEndAt(end).tourSt(state).build()).getTourId());
    }

    private CancelTourReservationRequest cancelRequest(String phone) {
        return json.convertValue(Map.of("tourTel", phone, "tourPwd", PIN), CancelTourReservationRequest.class);
    }

    private Room room(Building building, int number) {
        return rooms.saveAndFlush(Room.builder().building(building).roomNo(number).floor(1)
                .roomSize(new BigDecimal("20.00")).rentPrice(new BigDecimal("500000")).build());
    }

    private String state(Integer id) {
        return jdbc.queryForObject("SELECT tour_st FROM room_reservation WHERE tour_id = ?", String.class, id);
    }

    private int activeInRoom(Integer room, LocalDateTime start, LocalDateTime end) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM room_reservation WHERE room_id = ? AND tour_st IN ('requested','confirmed')
                  AND tour_start_at < ? AND tour_end_at > ?
                """, Integer.class, room, end, start);
    }

    private int activeCount() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM room_reservation WHERE building_id = ? AND tour_st IN ('requested','confirmed')
                """, Integer.class, f.building());
    }

    private void changeStatusOwned(Integer id, TourStatus status) {
        var previous = SecurityContextHolder.getContext().getAuthentication();
        try {
            AuthUser principal = new AuthUser("tour-race-owner-" + f.building(), "OWNER");
            SecurityContextHolder.getContext().setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
            tours.changeStatus(id, status);
        } finally {
            SecurityContextHolder.getContext().setAuthentication(previous);
        }
    }

    private int rowCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM room_reservation WHERE building_id = ?", Integer.class, f.building());
    }

    private void assertCreationEvents(int count) {
        assertThat(notificationCount("TOUR_REQ")).isEqualTo(count);
        assertSlackEvents(count);
    }

    private void assertSlackEvents(int count) {
        assertThat(mockingDetails(slack).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("sendTourReservationAlert")).count())
                .isEqualTo(count);
    }

    private long notificationCount(String type) {
        return mockingDetails(notifications).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("notifyUser"))
                .filter(invocation -> type.equals(invocation.getArgument(1))).count();
    }

    private String phone(int suffix) {
        return "010" + String.format("%08d", f.building() * 10 + suffix);
    }

    private static String hyphens(String phone) {
        return phone.substring(0, 3) + "-" + phone.substring(3, 7) + "-" + phone.substring(7);
    }

    private static void oneWinner(List<Outcome> results, ErrorCode expected) {
        assertThat(results.stream().filter(result -> result.failure() == null).count()).isEqualTo(1);
        assertThat(results.stream().filter(result -> result.failure() != null).toList()).singleElement()
                .satisfies(result -> assertFailure(result.failure(), expected));
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

    private record Fixture(Integer building, Integer room, Integer otherRoom) {}
    private record Outcome(Integer id, Throwable failure) {}
}
