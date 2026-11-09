package org.myweb.uniplace.domain.reservation.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.myweb.uniplace.domain.notification.application.NotificationService;
import org.myweb.uniplace.domain.property.domain.entity.Building;
import org.myweb.uniplace.domain.property.domain.entity.Room;
import org.myweb.uniplace.domain.property.repository.BuildingRepository;
import org.myweb.uniplace.domain.property.repository.RoomRepository;
import org.myweb.uniplace.domain.reservation.api.dto.request.CreateTourReservationRequest;
import org.myweb.uniplace.domain.reservation.application.TourReservationService;
import org.myweb.uniplace.domain.reservation.domain.entity.TourReservationEntity;
import org.myweb.uniplace.domain.reservation.domain.enums.TourStatus;
import org.myweb.uniplace.domain.reservation.repository.TourReservationRepository;
import org.myweb.uniplace.domain.user.domain.entity.User;
import org.myweb.uniplace.domain.user.domain.enums.UserRole;
import org.myweb.uniplace.domain.user.repository.UserRepository;
import org.myweb.uniplace.global.exception.ErrorCode;
import org.myweb.uniplace.global.security.JwtProvider;
import org.myweb.uniplace.global.slack.SlackNotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Exercises the real ADMIN route and committed state around sequential tour restoration. */
@SpringBootTest(properties = {
        "server.servlet.context-path=/api",
        "jwt.secret=tour-restoration-test-secret-with-at-least-32-bytes",
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
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Testcontainers
class AdminTourRestorationIntegrationTest {
    private static final LocalDateTime START = LocalDate.of(2100, 3, 11).atTime(10, 0);

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("admin_tour_restoration")
            .withUsername("tour_restoration_test")
            .withPassword("tour-restoration-test-only");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", MYSQL::getJdbcUrl);
        properties.add("spring.datasource.username", MYSQL::getUsername);
        properties.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired BuildingRepository buildings;
    @Autowired RoomRepository rooms;
    @Autowired UserRepository users;
    @Autowired TourReservationRepository tourRows;
    @Autowired TourReservationService tours;
    @Autowired JwtProvider jwt;
    @MockitoBean NotificationService notifications;
    @MockitoBean SlackNotificationService slack;

    private Fixture fixture;

    @BeforeEach
    void committedFixture() {
        fixture = new TransactionTemplate(transactions).execute(status -> {
            Building building = buildings.saveAndFlush(Building.builder().buildingNm("IBM01 tour fixture")
                    .buildingAddr("Test fixture address").build());
            Room first = room(building, 1);
            Room second = room(building, 2);
            String prefix = "ibm01-" + building.getBuildingId();
            user(prefix + "-admin", UserRole.admin);
            user(prefix + "-user", UserRole.user);
            return new Fixture(building.getBuildingId(), first.getRoomId(), second.getRoomId(),
                    prefix + "-admin", prefix + "-user");
        });
    }

    @Test
    void anInactiveTourCannotBeRestoredOverANewerActiveTourInTheSameRoom() throws Exception {
        Integer old = create(fixture.roomId(), phone(1), START);
        change(old, TourStatus.cancelled).andExpect(status().isOk());
        Integer replacement = create(fixture.roomId(), phone(2), START);
        clearInvocations(notifications, slack);

        MvcResult result = change(old, TourStatus.confirmed).andReturn();
        System.out.printf("IBM01 scope=room http=%d active=%d old=%s%n", result.getResponse().getStatus(),
                activeInRoom(START, START.plusHours(1)), state(old));

        assertError(result, ErrorCode.TOUR_RESERVATION_TIME_CONFLICT);
        assertThat(state(old)).isEqualTo("cancelled");
        assertThat(state(replacement)).isEqualTo("requested");
        assertThat(activeInRoom(START, START.plusHours(1))).isEqualTo(1);
        verifyNoInteractions(notifications, slack);
    }

    @Test
    void restorationChecksTheExactTelephoneTimeAcrossDifferentRooms() throws Exception {
        Integer old = create(fixture.roomId(), phone(1), START);
        change(old, TourStatus.ended).andExpect(status().isOk());
        Integer replacement = create(fixture.otherRoomId(), phone(1), START);
        clearInvocations(notifications, slack);

        MvcResult result = change(old, TourStatus.requested).andReturn();
        int active = jdbc.queryForObject("""
                SELECT COUNT(*) FROM room_reservation WHERE tour_tel = ? AND tour_start_at = ? AND tour_end_at = ?
                 AND tour_st IN ('requested','confirmed')
                """, Integer.class, phone(1), START, START.plusHours(1));
        System.out.printf("IBM01 scope=telephone http=%d active=%d old=%s%n", result.getResponse().getStatus(), active, state(old));

        assertError(result, ErrorCode.TOUR_RESERVATION_DUPLICATE);
        assertThat(state(old)).isEqualTo("ended");
        assertThat(state(replacement)).isEqualTo("requested");
        assertThat(active).isEqualTo(1);
        verifyNoInteractions(notifications, slack);
    }

    @Test
    void restorationKeepsTheStrictOverlapRuleForALegacyHalfHourTour() throws Exception {
        Integer old = create(fixture.roomId(), phone(1), START);
        change(old, TourStatus.cancelled).andExpect(status().isOk());
        new TransactionTemplate(transactions).executeWithoutResult(status -> tourRows.saveAndFlush(
                TourReservationEntity.builder().building(buildings.findById(fixture.buildingId()).orElseThrow())
                        .room(rooms.findById(fixture.roomId()).orElseThrow()).tourNm("Legacy fixture visitor")
                        .tourTel(phone(2)).tourPwd("2468").tourStartAt(START).tourEndAt(START.plusMinutes(30))
                        .tourSt(TourStatus.confirmed).build()));
        clearInvocations(notifications, slack);

        MvcResult result = change(old, TourStatus.confirmed).andReturn();

        assertError(result, ErrorCode.TOUR_RESERVATION_TIME_CONFLICT);
        assertThat(state(old)).isEqualTo("cancelled");
        assertThat(activeInRoom(START, START.plusHours(1))).isEqualTo(1);
        verifyNoInteractions(notifications, slack);
    }

    @Test
    void aClearSlotCanBeRestoredDespiteInactiveHistoryAndAnAdjacentActiveTour() throws Exception {
        Integer old = create(fixture.roomId(), phone(1), START);
        change(old, TourStatus.ended).andExpect(status().isOk());
        Integer inactive = create(fixture.roomId(), phone(1), START);
        change(inactive, TourStatus.cancelled).andExpect(status().isOk());
        Integer adjacent = create(fixture.roomId(), phone(1), START.plusHours(1));
        clearInvocations(notifications, slack);

        assertSuccess(change(old, TourStatus.confirmed).andReturn());
        // Already active transitions must not treat the same row as a conflicting tour.
        assertSuccess(change(old, TourStatus.requested).andReturn());

        assertThat(state(old)).isEqualTo("requested");
        assertThat(state(inactive)).isEqualTo("cancelled");
        assertThat(state(adjacent)).isEqualTo("requested");
        assertThat(activeInRoom(START, START.plusHours(1))).isEqualTo(1);
        verifyNoInteractions(notifications, slack);
    }

    @Test
    void theStatusQueryRemainsAdminOnlyAndReturnsTheExistingVoidEnvelope() throws Exception {
        Integer old = create(fixture.roomId(), phone(1), START);
        change(old, TourStatus.cancelled).andExpect(status().isOk());

        mvc.perform(statusRequest(old, TourStatus.confirmed)).andExpect(status().isUnauthorized());
        mvc.perform(statusRequest(old, TourStatus.confirmed).header(HttpHeaders.AUTHORIZATION,
                "Bearer " + jwt.createAccessToken(fixture.user(), "user"))).andExpect(status().isForbidden());
        assertThat(state(old)).isEqualTo("cancelled");
        assertError(change(-1, TourStatus.confirmed).andReturn(), ErrorCode.TOUR_STATUS_NOT_FOUND);
        assertSuccess(change(old, TourStatus.confirmed).andReturn());
        assertThat(state(old)).isEqualTo("confirmed");
    }

    private org.springframework.test.web.servlet.ResultActions change(Integer id, TourStatus status) throws Exception {
        return mvc.perform(statusRequest(id, status).header(HttpHeaders.AUTHORIZATION,
                "Bearer " + jwt.createAccessToken(fixture.admin(), "admin")));
    }

    private static MockHttpServletRequestBuilder statusRequest(Integer id, TourStatus status) {
        return put("/api/admin/tour-reservations/status/" + id).contextPath("/api").param("status", status.name());
    }

    private Integer create(Integer roomId, String tel, LocalDateTime start) {
        return tours.create(CreateTourReservationRequest.builder().buildingId(fixture.buildingId()).roomId(roomId)
                .tourStartAt(start).tourEndAt(start.plusHours(1)).tourNm("IBM01 fixture visitor")
                .tourTel(tel).tourPwd("2468").build()).getTourId();
    }

    private String state(Integer id) {
        return jdbc.queryForObject("SELECT tour_st FROM room_reservation WHERE tour_id = ?", String.class, id);
    }

    private int activeInRoom(LocalDateTime start, LocalDateTime end) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM room_reservation WHERE room_id = ? AND tour_st IN ('requested','confirmed')
                 AND tour_start_at < ? AND tour_end_at > ?
                """, Integer.class, fixture.roomId(), end, start);
    }

    private void assertError(MvcResult result, ErrorCode expected) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(expected.getStatus().value());
        JsonNode body = json.readTree(result.getResponse().getContentAsByteArray());
        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("errorCode").asText()).isEqualTo(expected.getCode());
    }

    private void assertSuccess(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json.readTree(result.getResponse().getContentAsByteArray());
        assertThat(body.path("success").asBoolean()).isTrue();
        assertThat(body.path("data").isNull()).isTrue();
        assertThat(body.path("errorCode").isNull()).isTrue();
        assertThat(body.path("message").isNull()).isTrue();
    }

    private String phone(int suffix) {
        return "010" + String.format("%08d", fixture.buildingId() * 10 + suffix);
    }

    private Room room(Building building, int number) {
        return rooms.saveAndFlush(Room.builder().building(building).roomNo(number).floor(1)
                .roomSize(new BigDecimal("20.00")).rentPrice(new BigDecimal("500000")).build());
    }

    private void user(String id, UserRole role) {
        users.saveAndFlush(User.builder().userId(id).userNm(id).userEmail(id + "@example.test").userPwd("test-only-hash")
                .userBirth(LocalDate.of(2000, 1, 1)).userTel("010-0000-0000").userNickname(id).userRole(role).build());
    }

    private record Fixture(Integer buildingId, Integer roomId, Integer otherRoomId, String admin, String user) {}
}
