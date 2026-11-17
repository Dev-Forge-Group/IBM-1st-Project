package org.myweb.uniplace.domain.reservation.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.uniplace.domain.notification.application.NotificationService;
import org.myweb.uniplace.domain.property.domain.entity.Building;
import org.myweb.uniplace.domain.property.domain.entity.Room;
import org.myweb.uniplace.domain.property.domain.enums.RoomStatus;
import org.myweb.uniplace.domain.property.repository.BuildingRepository;
import org.myweb.uniplace.domain.property.repository.RoomRepository;
import org.myweb.uniplace.domain.user.domain.entity.User;
import org.myweb.uniplace.domain.user.domain.enums.UserRole;
import org.myweb.uniplace.domain.user.repository.UserRepository;
import org.myweb.uniplace.global.exception.ErrorCode;
import org.myweb.uniplace.global.security.JwtProvider;
import org.myweb.uniplace.global.slack.SlackNotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** HTTP socket requests exercise committed state between discovery, credentials and administrator actions. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1", "server.servlet.context-path=/api",
        "jwt.secret=tour-http-journey-test-secret-with-at-least-32-bytes",
        "jwt.access-exp=3600000", "jwt.refresh-exp=86400000",
        "spring.mail.host=localhost", "spring.mail.port=2525",
        "spring.mail.username=local", "spring.mail.password=local",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.show-sql=false",
        "logging.level.org.hibernate.SQL=WARN", "payment.reconcile.enabled=false",
        "ai.fastapi.base-url=http://127.0.0.1:9", "slack.webhook-url=",
        "contract.status-transition-cron=-"
})
@Testcontainers
class TourHttpJourneyIntegrationTest {
    private static final String PIN = "2468";
    private static final LocalDate DATE = LocalDate.of(2100, 3, 11);
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("tour_http_journey").withUsername("tour_http_test")
            .withPassword("tour-http-test-only");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", MYSQL::getJdbcUrl);
        properties.add("spring.datasource.username", MYSQL::getUsername);
        properties.add("spring.datasource.password", MYSQL::getPassword);
    }

    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JwtProvider jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired BuildingRepository buildings;
    @Autowired RoomRepository rooms;
    @Autowired UserRepository users;
    @MockitoBean NotificationService notifications;
    @MockitoBean SlackNotificationService slack;

    private Fixture fixture;

    /** Optional local browser check holds this disposable server/DB; normal CI creates no extra test or skip. */
    @AfterAll
    static void optionalBrowserJourney(
            @Autowired BuildingRepository buildings, @Autowired RoomRepository rooms,
            @Autowired PlatformTransactionManager transactions, @Autowired ObjectMapper json,
            @LocalServerPort int port) throws Exception {
        String target = System.getenv("UNIPLACE_TOUR_BROWSER_READY");
        if (target == null || target.isBlank()) return;
        Map<String, Object> browser = new TransactionTemplate(transactions).execute(status -> {
            Building building = buildings.saveAndFlush(Building.builder().buildingNm("HTTP browser journey")
                    .buildingAddr("Synthetic browser fixture address").build());
            Room room = rooms.saveAndFlush(Room.builder().building(building).roomNo(701).floor(1)
                    .roomSize(new BigDecimal("20.00")).rentPrice(new BigDecimal("500000"))
                    .roomSt(RoomStatus.available).build());
            return Map.of("baseUrl", "http://127.0.0.1:" + port + "/api",
                    "buildingId", building.getBuildingId(), "buildingNm", building.getBuildingNm(),
                    "roomId", room.getRoomId(), "roomNo", room.getRoomNo(), "date", "2100-03-12");
        });
        Path ready = Path.of(target).toAbsolutePath();
        Path done = Path.of(ready + ".done");
        if (Files.exists(done, LinkOption.NOFOLLOW_LINKS)) throw new AssertionError("Browser completion marker already exists");
        Files.createDirectories(ready.getParent());
        Files.writeString(ready, json.writeValueAsString(browser), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        while (!Files.exists(done)) {
            if (System.nanoTime() >= deadline) throw new AssertionError("Browser verification did not finish before timeout");
            Thread.sleep(200);
        }
        assertThat(Files.readString(done)).isEqualTo("passed");
    }

    @BeforeEach
    void committedFixture() {
        fixture = new TransactionTemplate(transactions).execute(status -> {
            Building building = buildings.saveAndFlush(Building.builder().buildingNm("HTTP journey fixture")
                    .buildingAddr("Synthetic fixture address").build());
            Room first = room(building, 1, RoomStatus.available);
            Room second = room(building, 2, RoomStatus.available);
            room(building, 3, RoomStatus.contracted);
            Room deleted = room(building, 4, RoomStatus.available);
            deleted.softDelete();
            rooms.flush();
            String prefix = "http-tour-" + building.getBuildingId();
            for (UserRole role : UserRole.values()) {
                users.saveAndFlush(User.builder().userId(prefix + "-" + role.name()).userNm("HTTP actor")
                        .userEmail(prefix + "-" + role.name() + "@example.test").userPwd("synthetic-test-hash")
                        .userBirth(LocalDate.of(2000, 1, 1)).userTel("010-0000-0000")
                        .userNickname(prefix + "-" + role.name()).userRole(role).build());
            }
            return new Fixture(building.getBuildingId(), first.getRoomId(), second.getRoomId(), prefix);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"anonymous", "guest", "user", "tenant", "admin"})
    void publicActorsDiscoverBookFindAndCancelWithTheSameTelephoneCredentials(String actor) throws Exception {
        String token = token(actor);
        JsonNode slot = discoverSlot(token);
        String originalPhone = hyphens(phone(1));
        JsonNode created = create(fixture.roomId(), slot, originalPhone, token);
        int id = created.path("tourId").asInt();
        assertThat(id).isPositive();
        assertThat(created.path("tourSt").asText()).isEqualTo("requested");
        assertThat(created.path("tourTel").asText()).isEqualTo(originalPhone);
        assertThat(hasSlot(slots(token), slot)).isFalse();

        JsonNode history = lookup(phone(1), token);
        assertThat(history.path("page").asInt()).isEqualTo(1);
        assertThat(history.path("totalElements").asLong()).isEqualTo(1);
        assertThat(tour(history, id).path("tourSt").asText()).isEqualTo("requested");
        assertError(request("PUT", "/tour-reservations/cancel/" + id, token,
                credentials(phone(1), "9999")), ErrorCode.TOUR_RESERVATION_ALREADY_CANCELLED);
        assertThat(state(id)).isEqualTo("requested");

        JsonNode cancelled = data(request("PUT", "/tour-reservations/cancel/" + id, token,
                credentials(phone(1), PIN)));
        assertThat(cancelled.path("tourSt").asText()).isEqualTo("cancelled");
        assertThat(hasSlot(slots(token), slot)).isTrue();
        assertThat(tour(lookup(phone(1), token), id).path("tourSt").asText()).isEqualTo("cancelled");
        assertThat(data(request("PUT", "/tour-reservations/cancel/" + id, token,
                credentials(phone(1), PIN))).path("tourSt").asText()).isEqualTo("cancelled");
        assertThat(tourCount()).isEqualTo(1);
    }

    @Test
    void anEntireCommittedJourneyPreservesConflictsHistoryAndAdministratorRestoration() throws Exception {
        JsonNode slot = discoverSlot(null);
        JsonNode created = create(fixture.roomId(), slot, phone(1), null);
        int old = created.path("tourId").asInt();
        assertError(request("POST", "/tour-reservations", null,
                booking(fixture.roomId(), slot, phone(2))), ErrorCode.TOUR_RESERVATION_TIME_CONFLICT);
        assertError(request("POST", "/tour-reservations", null,
                booking(fixture.otherRoomId(), slot, hyphens(phone(1)))), ErrorCode.TOUR_RESERVATION_DUPLICATE);
        assertThat(tourCount()).isEqualTo(1);

        data(request("PUT", "/tour-reservations/cancel/" + old, null, credentials(phone(1), PIN)));
        int replacement = create(fixture.roomId(), slot, phone(2), null).path("tourId").asInt();
        String admin = token("admin");
        assertError(status(old, "confirmed", admin), ErrorCode.TOUR_RESERVATION_TIME_CONFLICT);
        assertThat(state(old)).isEqualTo("cancelled");
        assertThat(state(replacement)).isEqualTo("requested");
        assertThat(tourCount()).isEqualTo(2);
        assertThat(hasSlot(slots(null), slot)).isFalse();

        data(request("PUT", "/tour-reservations/cancel/" + replacement, null, credentials(phone(2), PIN)));
        assertThat(hasSlot(slots(null), slot)).isTrue();
        assertThat(data(status(old, "confirmed", admin)).isNull()).isTrue();
        assertThat(state(old)).isEqualTo("confirmed");
        assertThat(hasSlot(slots(null), slot)).isFalse();
        assertThat(tour(lookup(phone(1), null), old).path("tourSt").asText()).isEqualTo("confirmed");
        assertThat(data(status(old, "ended", admin)).isNull()).isTrue();
        assertThat(hasSlot(slots(null), slot)).isTrue();
        assertError(request("PUT", "/tour-reservations/cancel/" + old, null,
                credentials(phone(1), PIN)), ErrorCode.TOUR_RESERVATION_CANNOT_CANCEL);
        assertThat(state(old)).isEqualTo("ended");
        assertThat(tour(lookup(phone(1), null), old).path("tourSt").asText()).isEqualTo("ended");
        assertThat(tour(lookup(phone(2), null), replacement).path("tourSt").asText()).isEqualTo("cancelled");
        assertThat(tour(data(request("GET", "/admin/tour-reservations?size=1000", admin, null)), old)
                .path("tourSt").asText()).isEqualTo("ended");
    }

    @ParameterizedTest
    @ValueSource(strings = {"anonymous", "guest", "user", "tenant", "admin"})
    void administratorListAndStatusRequireTheAdministratorRole(String actor) throws Exception {
        JsonNode slot = discoverSlot(null);
        int id = create(fixture.roomId(), slot, phone(1), null).path("tourId").asInt();
        String token = token(actor);
        Reply list = request("GET", "/admin/tour-reservations?size=1000", token, null);
        Reply change = status(id, "confirmed", token);
        if ("admin".equals(actor)) {
            assertThat(tour(data(list), id).path("tourId").asInt()).isEqualTo(id);
            assertThat(data(change).isNull()).isTrue();
            assertThat(state(id)).isEqualTo("confirmed");
        } else {
            ErrorCode expected = "anonymous".equals(actor) ? ErrorCode.UNAUTHORIZED : ErrorCode.FORBIDDEN;
            assertError(list, expected);
            assertError(change, expected);
            assertThat(state(id)).isEqualTo("requested");
        }
        assertThat(tourCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"date", "number", "missing-date", "status"})
    void invalidRequestParametersReturnBadRequestWithoutLeakingInputOrMutatingTours(String kind) throws Exception {
        JsonNode slot = discoverSlot(null);
        int id = create(fixture.roomId(), slot, phone(1), null).path("tourId").asInt();
        String marker = "wire-invalid-" + kind + "-marker";
        String path = "/tour-reservations/slots?buildingId=" + fixture.buildingId() + "&roomId=" + fixture.roomId();
        Reply reply = switch (kind) {
            case "date" -> request("GET", path + "&date=" + marker, null, null);
            case "number" -> request("GET", "/tour-reservations/slots?buildingId=" + marker
                    + "&roomId=" + fixture.roomId() + "&date=" + DATE, null, null);
            case "missing-date" -> request("GET", path, null, null);
            default -> status(id, marker, token("admin"));
        };
        assertError(reply, ErrorCode.BAD_REQUEST);
        assertThat(reply.body()).doesNotContain(marker, "Exception", "org.springframework", "java.time");
        assertThat(tourCount()).isEqualTo(1);
        assertThat(state(id)).isEqualTo("requested");
    }

    private JsonNode discoverSlot(String token) throws Exception {
        JsonNode listedBuildings = data(request("GET", "/buildings?size=1000", token, null)).path("content");
        assertThat(containsId(listedBuildings, "buildingId", fixture.buildingId())).isTrue();
        JsonNode availableRooms = data(request("GET", "/tour-reservations/rooms?buildingId="
                + fixture.buildingId() + "&size=1000", token, null)).path("content");
        assertThat(availableRooms.size()).isEqualTo(2);
        assertThat(containsId(availableRooms, "roomId", fixture.roomId())).isTrue();
        assertThat(containsId(availableRooms, "roomId", fixture.otherRoomId())).isTrue();
        for (JsonNode room : availableRooms) {
            assertThat(room.path("buildingId").asInt()).isEqualTo(fixture.buildingId());
            assertThat(room.path("roomSt").asText()).isEqualTo("available");
        }
        JsonNode available = slots(token);
        assertThat(available.isArray()).isTrue();
        assertThat(available.isEmpty()).isFalse();
        return available.get(0);
    }

    private JsonNode slots(String token) throws Exception {
        JsonNode response = data(request("GET", "/tour-reservations/slots?buildingId=" + fixture.buildingId()
                + "&roomId=" + fixture.roomId() + "&date=" + DATE, token, null));
        assertThat(response.path("buildingId").asInt()).isEqualTo(fixture.buildingId());
        assertThat(response.path("roomId").asInt()).isEqualTo(fixture.roomId());
        return response.path("availableSlots");
    }

    private JsonNode create(int room, JsonNode slot, String telephone, String token) throws Exception {
        JsonNode created = data(request("POST", "/tour-reservations", token, booking(room, slot, telephone)));
        assertThat(created.path("buildingId").asInt()).isEqualTo(fixture.buildingId());
        assertThat(created.path("roomId").asInt()).isEqualTo(room);
        assertThat(created.path("tourStartAt").asText()).isEqualTo(slot.path("startAt").asText());
        assertThat(created.path("tourEndAt").asText()).isEqualTo(slot.path("endAt").asText());
        return created;
    }

    private Map<String, Object> booking(int room, JsonNode slot, String telephone) {
        return Map.of("buildingId", fixture.buildingId(), "roomId", room,
                "tourStartAt", slot.path("startAt").asText(), "tourEndAt", slot.path("endAt").asText(),
                "tourNm", "HTTP fixture visitor", "tourTel", telephone, "tourPwd", PIN);
    }

    private Map<String, String> credentials(String telephone, String pin) {
        return Map.of("tourTel", telephone, "tourPwd", pin);
    }

    private JsonNode lookup(String telephone, String token) throws Exception {
        return data(request("POST", "/tour-reservations/lookup", token, credentials(telephone, PIN)));
    }

    private Reply status(int id, String status, String token) throws Exception {
        return request("PUT", "/admin/tour-reservations/status/" + id + "?status=" + status, token, null);
    }

    private Reply request(String method, String path, String token, Object payload) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api" + path))
                .timeout(Duration.ofSeconds(15)).header("Accept", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.noBody();
        if (payload != null) {
            request.header("Content-Type", "application/json");
            body = HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(payload));
        }
        HttpResponse<String> response = HTTP.send(request.method(method, body).build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body());
    }

    private JsonNode data(Reply reply) throws Exception {
        assertThat(reply.status()).isEqualTo(200);
        JsonNode body = json.readTree(reply.body());
        assertThat(body.path("success").asBoolean()).isTrue();
        assertThat(body.path("errorCode").isNull()).isTrue();
        assertThat(reply.body()).doesNotContain("\"tourPwd\"", "\"" + PIN + "\"");
        return body.path("data");
    }

    private void assertError(Reply reply, ErrorCode code) throws Exception {
        assertThat(reply.status()).isEqualTo(code.getStatus().value());
        JsonNode body = json.readTree(reply.body());
        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("errorCode").asText()).isEqualTo(code.getCode());
        assertThat(body.path("data").isNull()).isTrue();
    }

    private JsonNode tour(JsonNode page, int id) {
        for (JsonNode item : page.path("content")) {
            if (item.path("tourId").asInt() == id) return item;
        }
        throw new AssertionError("Expected tour is missing from HTTP history");
    }

    private boolean containsId(JsonNode array, String key, int id) {
        for (JsonNode item : array) if (item.path(key).asInt() == id) return true;
        return false;
    }

    private boolean hasSlot(JsonNode available, JsonNode expected) {
        for (JsonNode slot : available) {
            if (slot.path("startAt").equals(expected.path("startAt"))
                    && slot.path("endAt").equals(expected.path("endAt"))) return true;
        }
        return false;
    }

    private String token(String actor) {
        return switch (actor) {
            case "anonymous" -> null;
            case "guest" -> jwt.createAccessToken("GUEST_" + fixture.prefix(), "guest");
            default -> jwt.createAccessToken(fixture.prefix() + "-" + actor, actor);
        };
    }

    private String phone(int suffix) {
        return "010" + String.format(Locale.ROOT, "%08d", fixture.buildingId() * 10 + suffix);
    }

    private String hyphens(String telephone) {
        return telephone.substring(0, 3) + "-" + telephone.substring(3, 7) + "-" + telephone.substring(7);
    }

    private String state(int id) {
        return jdbc.queryForObject("SELECT tour_st FROM room_reservation WHERE tour_id=?", String.class, id);
    }

    private int tourCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM room_reservation WHERE building_id=?", Integer.class, fixture.buildingId());
    }

    private Room room(Building building, int number, RoomStatus status) {
        return rooms.saveAndFlush(Room.builder().building(building).roomNo(number).floor(1)
                .roomSize(new BigDecimal("20.00")).rentPrice(new BigDecimal("500000")).roomSt(status).build());
    }

    private record Reply(int status, String body) {}
    private record Fixture(int buildingId, int roomId, int otherRoomId, String prefix) {}
}
