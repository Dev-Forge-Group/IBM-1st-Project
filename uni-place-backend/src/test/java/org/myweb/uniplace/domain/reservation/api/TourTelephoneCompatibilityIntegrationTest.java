package org.myweb.uniplace.domain.reservation.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.myweb.uniplace.domain.ai.application.gateway.AiGateway;
import org.myweb.uniplace.domain.ai.application.gateway.dto.AiGatewayRequest;
import org.myweb.uniplace.domain.ai.application.usecase.MyTourUseCase;
import org.myweb.uniplace.domain.ai.domain.AiIntent;
import org.myweb.uniplace.domain.notification.application.NotificationService;
import org.myweb.uniplace.domain.property.domain.entity.Building;
import org.myweb.uniplace.domain.property.domain.entity.Room;
import org.myweb.uniplace.domain.property.repository.BuildingRepository;
import org.myweb.uniplace.domain.property.repository.RoomRepository;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Public credential matching and its direct AI consumer, using real MySQL and the security filters. */
@SpringBootTest(properties = {
        "server.servlet.context-path=/api",
        "jwt.secret=tour-telephone-test-secret-with-at-least-32-bytes",
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
class TourTelephoneCompatibilityIntegrationTest {
    private static final String PIN = "2468";
    private static final LocalDateTime START = LocalDate.of(2100, 3, 11).atTime(10, 0);

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("tour_telephone_compatibility")
            .withUsername("telephone_test")
            .withPassword("telephone-test-only");

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
    @Autowired TourReservationRepository tours;
    @Autowired MyTourUseCase myTours;
    @Autowired JwtProvider jwt;
    @MockitoBean NotificationService notifications;
    @MockitoBean SlackNotificationService slack;
    @MockitoBean AiGateway aiGateway;

    private Fixture f;

    @BeforeEach
    void committedFixture() {
        f = new TransactionTemplate(transactions).execute(status -> {
            Building building = buildings.saveAndFlush(Building.builder().buildingNm("Telephone compatibility fixture")
                    .buildingAddr("Test fixture address").build());
            Room first = room(building, 1);
            Room second = room(building, 2);
            String admin = "telephone-admin-" + building.getBuildingId();
            users.saveAndFlush(User.builder().userId(admin).userNm(admin).userEmail(admin + "@example.test")
                    .userPwd("test-only-hash").userBirth(LocalDate.of(2000, 1, 1)).userTel("010-0000-0000")
                    .userNickname(admin).userRole(UserRole.admin).build());
            return new Fixture(building.getBuildingId(), first.getRoomId(), second.getRoomId(), admin);
        });
        clearInvocations(notifications, slack, aiGateway);
    }

    @Test
    void aDigitsReservationCanBeLookedUpAndCancelledWithHyphensWithoutRewritingIt() throws Exception {
        String original = digits(1);
        JsonNode created = create(f.room(), original, START, 200).path("data");
        int id = created.path("tourId").asInt();
        assertPreservedTelephone(created, original);
        assertThat(lookup(hyphens(original), PIN, 1, 10).path("content").get(0).path("tourId").asInt()).isEqualTo(id);
        JsonNode cancelled = cancel(id, hyphens(original), PIN, 200).path("data");
        assertThat(cancelled.path("tourSt").asText()).isEqualTo("cancelled");
        assertPreservedTelephone(cancelled, original);
        assertStoredTelephone(id, original);
    }

    @Test
    void aHyphenatedReservationCanBeLookedUpAndCancelledWithDigitsWithoutRewritingIt() throws Exception {
        String original = hyphens(digits(1));
        int id = create(f.room(), original, START, 200).path("data").path("tourId").asInt();
        JsonNode found = lookup(digits(1), PIN, 1, 10).path("content").get(0);
        assertThat(found.path("tourId").asInt()).isEqualTo(id);
        assertPreservedTelephone(found, original);
        assertThat(cancel(id, digits(1), PIN, 200).path("data").path("tourSt").asText()).isEqualTo("cancelled");
        assertStoredTelephone(id, original);
    }

    @Test
    void legacyNonstandardAsciiHyphenPlacementStillMatchesTheSameMobileDigits() throws Exception {
        String number = digits(1);
        String original = "-" + number.substring(0, 1) + "-" + number.substring(1, 5) + "--" + number.substring(5);
        int id = legacy(original, PIN, TourStatus.requested, f.room(), START);
        assertThat(lookup(number, PIN, 1, 10).path("content").get(0).path("tourId").asInt()).isEqualTo(id);
        assertThat(lookup(hyphens(number), PIN, 1, 10).path("content").get(0).path("tourId").asInt()).isEqualTo(id);
        cancel(id, hyphens(number), PIN, 200);
        assertStoredTelephone(id, original);
    }

    @Test
    void anotherPresentationCannotCreateTheSameActiveTelephoneTimeInAnotherRoom() throws Exception {
        create(f.room(), digits(1), START, 200);
        clearInvocations(notifications, slack);
        JsonNode refused = create(f.otherRoom(), hyphens(digits(1)), START, 409);
        assertThat(refused.path("errorCode").asText()).isEqualTo(ErrorCode.TOUR_RESERVATION_DUPLICATE.getCode());
        assertThat(activeCount()).isEqualTo(1);
        assertThat(rowCount()).isEqualTo(1);
        assertNoExternalEvents();
    }

    @Test
    void anAdminCannotRestoreInactiveHistoryOverAnEquivalentActiveTelephone() throws Exception {
        int old = legacy(hyphens(digits(1)), PIN, TourStatus.cancelled, f.room(), START);
        int active = create(f.otherRoom(), digits(1), START, 200).path("data").path("tourId").asInt();
        clearInvocations(notifications, slack);
        JsonNode refused = send(put("/api/admin/tour-reservations/status/" + old).contextPath("/api")
                .param("status", "confirmed").header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + jwt.createAccessToken(f.admin(), "admin")), null, 409);
        assertThat(refused.path("errorCode").asText()).isEqualTo(ErrorCode.TOUR_RESERVATION_DUPLICATE.getCode());
        assertThat(state(old)).isEqualTo("cancelled");
        assertThat(state(active)).isEqualTo("requested");
        assertThat(activeCount()).isEqualTo(1);
        assertNoExternalEvents();
    }

    @Test
    void wrongPinsOtherPhonesAndUnsupportedPresentationAliasesCannotReadOrCancel() throws Exception {
        int id = legacy(digits(1), PIN, TourStatus.requested, f.room(), START);
        lookupFailure(digits(1), "1357");
        cancel(id, digits(1), "1357", 400);
        String number = digits(1);
        List<String> unsupported = List.of(digits(2), " " + number, number.substring(0, 3) + " " + number.substring(3),
                "+82" + number.substring(1), "x" + number, hyphens(number).replace('-', '\u2013'),
                fullWidth(hyphens(number)));
        for (String other : unsupported) {
            lookupFailure(other, PIN);
            cancel(id, other, PIN, 400);
        }
        assertThat(state(id)).isEqualTo("requested");
        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    void unsupportedLegacyValuesKeepTheirRawLookupWithoutJoiningADomesticAlias() throws Exception {
        String opaque = "legacy-id-" + f.building();
        int opaqueId = legacy(opaque, PIN, TourStatus.requested, f.room(), START);
        assertThat(lookup(opaque, PIN, 1, 10).path("content").get(0).path("tourId").asInt()).isEqualTo(opaqueId);
        lookupFailure(opaque.replace("-", ""), PIN);
        String unicode = fullWidth(hyphens(digits(1)));
        int unicodeId = legacy(unicode, PIN, TourStatus.requested, f.otherRoom(), START.plusHours(1));
        lookupFailure(digits(1), PIN);
        cancel(unicodeId, digits(1), PIN, 400);
        assertThat(lookup(unicode, PIN, 1, 10).path("content").get(0).path("tourId").asInt()).isEqualTo(unicodeId);
        assertStoredTelephone(opaqueId, opaque);
        assertStoredTelephone(unicodeId, unicode);
    }

    @Test
    void inactiveHistoryRemainsVisibleAndEndedCancellationStillRefusesWhileTheSlotCanBeReused() throws Exception {
        String original = hyphens(digits(1));
        int cancelled = legacy(original, PIN, TourStatus.cancelled, f.room(), START);
        int ended = legacy(original, PIN, TourStatus.ended, f.otherRoom(), START);
        assertThat(lookup(digits(1), PIN, 1, 10).path("totalElements").asInt()).isEqualTo(2);
        clearInvocations(notifications, slack);
        cancel(cancelled, digits(1), PIN, 200);
        JsonNode refused = cancel(ended, digits(1), PIN, 400);
        assertThat(refused.path("errorCode").asText()).isEqualTo(ErrorCode.TOUR_RESERVATION_CANNOT_CANCEL.getCode());
        assertNoExternalEvents();
        create(f.room(), digits(1), START, 200);
        assertThat(activeCount()).isEqualTo(1);
        assertThat(lookup(digits(1), PIN, 1, 10).path("totalElements").asInt()).isEqualTo(3);
        assertStoredTelephone(cancelled, original);
        assertStoredTelephone(ended, original);
    }

    @Test
    void paginationAndRepositoryInactiveFilteringApplyAfterTelephoneMatching() throws Exception {
        String number = digits(1);
        int first = legacy(number, PIN, TourStatus.requested, f.room(), START);
        int second = legacy(hyphens(number), PIN, TourStatus.ended, f.room(), START.plusDays(1));
        int third = legacy(number.substring(0, 2) + "-" + number.substring(2), PIN,
                TourStatus.confirmed, f.room(), START.plusDays(2));
        JsonNode pageOne = lookup(number, PIN, 1, 2);
        assertThat(pageOne.path("totalElements").asInt()).isEqualTo(3);
        assertThat(pageOne.path("totalPages").asInt()).isEqualTo(2);
        assertThat(ids(pageOne.path("content"))).containsExactly(first, second);
        assertThat(ids(lookup(hyphens(number), PIN, 2, 2).path("content"))).containsExactly(third);
        var active = tours.findByTourTelAndTourPwdAndTourStNotIn(number, PIN,
                List.of(TourStatus.cancelled, TourStatus.ended), PageRequest.of(0, 1, Sort.by("tourId").descending()));
        assertThat(active.getTotalElements()).isEqualTo(2);
        assertThat(active.getContent()).extracting(TourReservationEntity::getTourId).containsExactly(third);
    }

    @Test
    void directAiLookupKeepsItsFirstPageAndEmptyResultContractWithoutExpandingPinAliases() {
        String number = digits(1);
        List<Integer> expected = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            expected.add(legacy(hyphens(number), PIN, TourStatus.requested, f.room(), START.plusDays(i)));
        }
        List<Map<String, Object>> found = aiItems(number, PIN);
        assertThat(found.size()).isEqualTo(10);
        assertThat(found.stream().map(item -> (Integer) item.get("tour_id")).toList()).allMatch(expected::contains);
        assertThat(found.stream().anyMatch(item -> item.containsKey("tour_pwd") || item.containsKey("tour_tel"))).isFalse();
        assertThat(aiItems(number, "1357").size()).isZero();
        assertThat(aiItems(number, fullWidth(PIN)).size()).isZero();
        assertThat(aiItems(number, PIN + " ").size()).isZero();
        legacy(hyphens(digits(2)), fullWidth(PIN), TourStatus.requested, f.otherRoom(), START);
        assertThat(aiItems(digits(2), PIN).size()).isZero();
    }

    private JsonNode create(int room, String phone, LocalDateTime start, int expected) throws Exception {
        return send(post("/api/tour-reservations").contextPath("/api"), Map.of("buildingId", f.building(), "roomId", room,
                "tourNm", "Telephone fixture visitor", "tourTel", phone, "tourPwd", PIN,
                "tourStartAt", start.toString(), "tourEndAt", start.plusHours(1).toString()), expected);
    }

    private JsonNode lookup(String phone, String pin, int page, int size) throws Exception {
        return send(post("/api/tour-reservations/lookup").contextPath("/api").param("page", Integer.toString(page))
                .param("size", Integer.toString(size)).param("sort", "tourStartAt").param("direct", "ASC"),
                credentials(phone, pin), 200).path("data");
    }

    private void lookupFailure(String phone, String pin) throws Exception {
        send(post("/api/tour-reservations/lookup").contextPath("/api"), credentials(phone, pin), 400);
    }

    private JsonNode cancel(int id, String phone, String pin, int expected) throws Exception {
        return send(put("/api/tour-reservations/cancel/" + id).contextPath("/api"), credentials(phone, pin), expected);
    }

    private JsonNode send(MockHttpServletRequestBuilder request, Object body, int expected) throws Exception {
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
        var response = mvc.perform(request).andReturn().getResponse();
        JsonNode parsed = json.readTree(response.getContentAsByteArray());
        assertThat(response.getStatus()).as("HTTP result with errorCode=%s", parsed.path("errorCode").asText()).isEqualTo(expected);
        assertThat(parsed.path("success").asBoolean()).isEqualTo(expected == 200);
        return parsed;
    }

    private int legacy(String phone, String pin, TourStatus state, int room, LocalDateTime start) {
        return new TransactionTemplate(transactions).execute(status -> tours.saveAndFlush(TourReservationEntity.builder()
                .building(buildings.findById(f.building()).orElseThrow()).room(rooms.findById(room).orElseThrow())
                .tourNm("Legacy fixture visitor").tourTel(phone).tourPwd(pin).tourStartAt(start)
                .tourEndAt(start.plusHours(1)).tourSt(state).build()).getTourId());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> aiItems(String phone, String pin) {
        clearInvocations(aiGateway);
        myTours.execute(AiGatewayRequest.builder().intent(AiIntent.MY_TOUR).slots(credentials(phone, pin)).build());
        assertThat(mockingDetails(aiGateway).getInvocations().size()).isEqualTo(1);
        ArgumentCaptor<AiGatewayRequest> captured = ArgumentCaptor.forClass(AiGatewayRequest.class);
        verify(aiGateway).execute(captured.capture());
        return (List<Map<String, Object>>) captured.getValue().getSlots().get("items");
    }

    private void assertNoExternalEvents() {
        assertThat(mockingDetails(notifications).getInvocations().size()).isZero();
        assertThat(mockingDetails(slack).getInvocations().size()).isZero();
    }

    private void assertStoredTelephone(int id, String expected) {
        String stored = jdbc.queryForObject("SELECT tour_tel FROM room_reservation WHERE tour_id=?", String.class, id);
        assertThat(expected.equals(stored)).as("stored telephone representation preserved").isTrue();
    }

    private static void assertPreservedTelephone(JsonNode response, String expected) {
        assertThat(expected.equals(response.path("tourTel").asText())).as("response telephone representation preserved").isTrue();
        assertThat(response.has("tourPwd")).isFalse();
    }

    private String state(int id) {
        return jdbc.queryForObject("SELECT tour_st FROM room_reservation WHERE tour_id=?", String.class, id);
    }

    private int activeCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM room_reservation WHERE building_id=? AND tour_st IN ('requested','confirmed')", Integer.class, f.building());
    }

    private int rowCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM room_reservation WHERE building_id=?", Integer.class, f.building());
    }

    private static List<Integer> ids(JsonNode content) {
        List<Integer> result = new ArrayList<>();
        content.forEach(row -> result.add(row.path("tourId").asInt()));
        return result;
    }

    private static Map<String, Object> credentials(String phone, String pin) {
        return Map.of("tourTel", phone, "tourPwd", pin);
    }

    private String digits(int suffix) {
        return "010" + String.format("%08d", f.building() * 10 + suffix);
    }

    private static String hyphens(String digits) {
        return digits.substring(0, 3) + "-" + digits.substring(3, 7) + "-" + digits.substring(7);
    }

    private static String fullWidth(String value) {
        StringBuilder result = new StringBuilder();
        value.chars().forEach(c -> result.append((char) (c >= '0' && c <= '9' ? c + '\uff10' - '0' : c)));
        return result.toString();
    }

    private Room room(Building building, int number) {
        return rooms.saveAndFlush(Room.builder().building(building).roomNo(number).floor(1)
                .roomSize(new BigDecimal("20.00")).rentPrice(new BigDecimal("500000")).build());
    }

    private record Fixture(Integer building, Integer room, Integer otherRoom, String admin) {}
}
