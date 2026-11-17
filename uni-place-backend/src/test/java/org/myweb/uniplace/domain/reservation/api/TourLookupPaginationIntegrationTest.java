package org.myweb.uniplace.domain.reservation.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.uniplace.domain.notification.application.NotificationService;
import org.myweb.uniplace.domain.property.domain.entity.Building;
import org.myweb.uniplace.domain.property.domain.entity.Room;
import org.myweb.uniplace.domain.property.domain.enums.RoomStatus;
import org.myweb.uniplace.domain.property.repository.BuildingRepository;
import org.myweb.uniplace.domain.property.repository.RoomRepository;
import org.myweb.uniplace.domain.reservation.domain.entity.TourReservationEntity;
import org.myweb.uniplace.domain.reservation.domain.enums.TourStatus;
import org.myweb.uniplace.domain.reservation.repository.TourReservationRepository;
import org.myweb.uniplace.global.exception.ErrorCode;
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

/** Actual HTTP lookup pagination observes committed mixed-status history and credential boundaries. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1", "server.servlet.context-path=/api",
        "jwt.secret=tour-lookup-test-secret-with-at-least-32-bytes",
        "jwt.access-exp=3600000", "jwt.refresh-exp=86400000",
        "spring.mail.host=localhost", "spring.mail.port=2525",
        "spring.mail.username=local", "spring.mail.password=local",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.show-sql=false",
        "logging.level.org.hibernate.SQL=WARN", "payment.reconcile.enabled=false",
        "ai.fastapi.base-url=http://127.0.0.1:9", "slack.webhook-url=",
        "contract.status-transition-cron=-"
})
@Testcontainers
class TourLookupPaginationIntegrationTest {
    private static final String PIN = "2468";
    private static final String OTHER_PIN = "1357";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("tour_lookup_pages").withUsername("tour_lookup_test")
            .withPassword("tour-lookup-test-only");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", MYSQL::getJdbcUrl);
        properties.add("spring.datasource.username", MYSQL::getUsername);
        properties.add("spring.datasource.password", MYSQL::getPassword);
    }

    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired BuildingRepository buildings;
    @Autowired RoomRepository rooms;
    @Autowired TourReservationRepository tours;
    @MockitoBean NotificationService notifications;
    @MockitoBean SlackNotificationService slack;

    private Fixture fixture;

    @BeforeEach
    void committedMixedHistory() {
        fixture = new TransactionTemplate(transactions).execute(status -> {
            Building building = buildings.saveAndFlush(Building.builder().buildingNm("Lookup page fixture")
                    .buildingAddr("Synthetic fixture address").build());
            Room room = rooms.saveAndFlush(Room.builder().building(building).roomNo(101).floor(1)
                    .roomSize(new BigDecimal("20.00")).rentPrice(new BigDecimal("500000"))
                    .roomSt(RoomStatus.available).build());
            String phone = phone(building.getBuildingId(), 1);
            List<Integer> all = new ArrayList<>();
            List<Integer> requested = new ArrayList<>();
            Map<Integer, String> originals = new HashMap<>();
            for (int index = 0; index < 22; index++) {
                TourStatus state = index % 2 == 0 ? TourStatus.requested : TourStatus.cancelled;
                String stored = index % 3 == 0 ? hyphens(phone) : phone;
                TourReservationEntity row = row(building, room, index, stored, PIN, state);
                all.add(row.getTourId());
                originals.put(row.getTourId(), stored);
                if (state == TourStatus.requested) requested.add(row.getTourId());
            }
            // Neither the other PIN nor the other telephone belongs in content or filtered counts.
            row(building, room, 23, phone, OTHER_PIN, TourStatus.requested);
            row(building, room, 24, phone(building.getBuildingId(), 2), PIN, TourStatus.requested);
            return new Fixture(building.getBuildingId(), phone, List.copyOf(all),
                    List.copyOf(requested), Map.copyOf(originals));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"ASC", "DESC"})
    void statusIsAppliedBeforePaginationWithExactCountOrderingAndTelephoneAliases(String direction) throws Exception {
        List<Integer> expected = new ArrayList<>(fixture.requested());
        if ("DESC".equals(direction)) Collections.reverse(expected);

        JsonNode first = data(lookup(fixture.phone(), PIN, "requested", 1, 10, direction));
        assertPage(first, 1, 10, 11, 2);
        assertThat(ids(first)).containsExactlyElementsOf(expected.subList(0, 10));
        assertStatusAndOriginals(first, "requested");

        JsonNode second = data(lookup(hyphens(fixture.phone()), PIN, "requested", 2, 10, direction));
        assertPage(second, 2, 10, 11, 2);
        assertThat(ids(second)).containsExactly(expected.get(10));
        assertStatusAndOriginals(second, "requested");
        assertThat(tourCount()).isEqualTo(24);
    }

    @ParameterizedTest
    @ValueSource(strings = {"confirmed", "ended"})
    void aValidCredentialCanHaveAnEmptyStatusPage(String status) throws Exception {
        JsonNode result = data(lookup(hyphens(fixture.phone()), PIN, status, 1, 10, "ASC"));
        assertPage(result, 1, 10, 0, 0);
        assertThat(ids(result)).isEmpty();
        assertThat(tourCount()).isEqualTo(24);
    }

    @Test
    void filteredOutOfRangeIsEmptyButTheLegacyOmittedStatusStillRejectsAnEmptyPage() throws Exception {
        JsonNode result = data(lookup(fixture.phone(), PIN, "requested", 3, 10, "ASC"));
        assertPage(result, 3, 10, 11, 2);
        assertThat(ids(result)).isEmpty();
        assertError(lookup(fixture.phone(), PIN, null, 4, 10, "ASC"), ErrorCode.BAD_REQUEST);
    }

    @Test
    void omittedStatusKeepsAllHistoryAndTheOriginalPageResponseShape() throws Exception {
        JsonNode result = data(lookup(hyphens(fixture.phone()), PIN, null, 1, 50, "ASC"));
        assertPage(result, 1, 50, 22, 1);
        assertThat(ids(result)).containsExactlyElementsOf(fixture.all());
        Set<String> fields = new java.util.HashSet<>();
        result.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("content", "page", "size", "totalElements", "totalPages");
        for (JsonNode row : result.path("content")) {
            assertThat(List.of("requested", "cancelled")).contains(row.path("tourSt").asText());
            assertThat(row.path("tourTel").asText().equals(fixture.originals().get(row.path("tourId").asInt()))).isTrue();
        }
    }

    @ParameterizedTest
    @CsvSource({"phone,false", "phone,true", "pin,false", "pin,true"})
    void invalidCredentialsStayRejectedWithOrWithoutAStatus(String changed, boolean filtered) throws Exception {
        String phone = "phone".equals(changed) ? phone(fixture.buildingId(), 9) : fixture.phone();
        String pin = "pin".equals(changed) ? "9999" : PIN;
        assertError(lookup(phone, pin, filtered ? "confirmed" : null, 1, 10, "ASC"), ErrorCode.BAD_REQUEST);
        assertThat(tourCount()).isEqualTo(24);
    }

    @ParameterizedTest
    @ValueSource(strings = {"wire-invalid-status-marker", "all", "REQUESTED"})
    void anUnknownStatusIsBadRequestWithoutInputOrExceptionEcho(String status) throws Exception {
        Reply reply = lookup(fixture.phone(), PIN, status, 1, 10, "ASC");
        assertError(reply, ErrorCode.BAD_REQUEST);
        assertThat(reply.body().contains(status)).isFalse();
        assertThat(reply.body().contains("Exception")).isFalse();
        assertThat(tourCount()).isEqualTo(24);
    }

    @Test
    void cancellationShrinksTheFilteredLastPageWithoutLosingHistory() throws Exception {
        JsonNode last = data(lookup(fixture.phone(), PIN, "requested", 2, 10, "ASC"));
        assertPage(last, 2, 10, 11, 2);
        assertThat(ids(last)).containsExactly(fixture.requested().get(10));
        int cancelledId = ids(last).get(0);

        JsonNode cancelled = data(request("PUT", "/tour-reservations/cancel/" + cancelledId,
                Map.of("tourTel", hyphens(fixture.phone()), "tourPwd", PIN)));
        assertThat(cancelled.path("tourSt").asText()).isEqualTo("cancelled");
        assertThat(cancelled.path("tourTel").asText().equals(fixture.originals().get(cancelledId))).isTrue();

        JsonNode emptied = data(lookup(fixture.phone(), PIN, "requested", 2, 10, "ASC"));
        assertPage(emptied, 2, 10, 10, 1);
        assertThat(ids(emptied)).isEmpty();
        JsonNode first = data(lookup(hyphens(fixture.phone()), PIN, "requested", 1, 10, "ASC"));
        assertPage(first, 1, 10, 10, 1);
        assertThat(ids(first)).containsExactlyElementsOf(fixture.requested().subList(0, 10));
        JsonNode cancelledHistory = data(lookup(fixture.phone(), PIN, "cancelled", 1, 20, "ASC"));
        assertPage(cancelledHistory, 1, 20, 12, 1);
        assertStatusAndOriginals(cancelledHistory, "cancelled");
        assertThat(ids(cancelledHistory)).contains(cancelledId);
        assertThat(ids(data(lookup(fixture.phone(), PIN, null, 1, 50, "ASC"))))
                .containsExactlyElementsOf(fixture.all());
        assertThat(tourCount()).isEqualTo(24);
    }

    private TourReservationEntity row(Building building, Room room, int offset, String phone, String pin, TourStatus status) {
        var start = LocalDate.of(2100, 3, 11).plusDays(offset).atTime(9, 0);
        return tours.saveAndFlush(TourReservationEntity.builder().building(building).room(room)
                .tourStartAt(start).tourEndAt(start.plusHours(1)).tourNm("Lookup fixture visitor")
                .tourTel(phone).tourPwd(pin).tourSt(status).build());
    }

    private Reply lookup(String phone, String pin, String status, int page, int size, String direction) throws Exception {
        String path = "/tour-reservations/lookup?page=" + page + "&size=" + size + "&sort=tourId&direct=" + direction;
        if (status != null) path += "&status=" + status;
        return request("POST", path, Map.of("tourTel", phone, "tourPwd", pin));
    }

    private Reply request(String method, String path, Object payload) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api" + path))
                .timeout(Duration.ofSeconds(15)).header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(payload))).build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body());
    }

    private JsonNode data(Reply reply) throws Exception {
        assertThat(reply.status()).isEqualTo(200);
        JsonNode body = json.readTree(reply.body());
        assertThat(body.path("success").asBoolean()).isTrue();
        assertThat(body.path("errorCode").isNull()).isTrue();
        assertThat(reply.body().contains("\"tourPwd\"") || reply.body().contains("\"" + PIN + "\"")
                || reply.body().contains("\"" + OTHER_PIN + "\"")).isFalse();
        return body.path("data");
    }

    private void assertError(Reply reply, ErrorCode expected) throws Exception {
        assertThat(reply.status()).isEqualTo(expected.getStatus().value());
        JsonNode body = json.readTree(reply.body());
        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("errorCode").asText()).isEqualTo(expected.getCode());
        assertThat(body.path("data").isNull()).isTrue();
    }

    private void assertPage(JsonNode page, int number, int size, int total, int pages) {
        assertThat(page.path("page").asInt()).isEqualTo(number);
        assertThat(page.path("size").asInt()).isEqualTo(size);
        assertThat(page.path("totalElements").asLong()).isEqualTo(total);
        assertThat(page.path("totalPages").asInt()).isEqualTo(pages);
    }

    private void assertStatusAndOriginals(JsonNode page, String status) {
        for (JsonNode row : page.path("content")) {
            assertThat(row.path("tourSt").asText()).isEqualTo(status);
            assertThat(row.path("tourTel").asText().equals(fixture.originals().get(row.path("tourId").asInt()))).isTrue();
        }
    }

    private List<Integer> ids(JsonNode page) {
        List<Integer> result = new ArrayList<>();
        for (JsonNode item : page.path("content")) result.add(item.path("tourId").asInt());
        return result;
    }

    private static String phone(int building, int suffix) {
        return "010" + String.format(Locale.ROOT, "%08d", building * 10 + suffix);
    }

    private static String hyphens(String phone) {
        return phone.substring(0, 3) + "-" + phone.substring(3, 7) + "-" + phone.substring(7);
    }

    private int tourCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM room_reservation WHERE building_id=?", Integer.class, fixture.buildingId());
    }

    private record Reply(int status, String body) {}
    private record Fixture(int buildingId, String phone, List<Integer> all,
                           List<Integer> requested, Map<Integer, String> originals) {}
}
