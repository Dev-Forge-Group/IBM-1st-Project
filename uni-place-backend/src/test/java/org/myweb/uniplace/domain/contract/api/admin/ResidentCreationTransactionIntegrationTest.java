package org.myweb.uniplace.domain.contract.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.uniplace.domain.contract.api.dto.request.ResidentCreateRequest;
import org.myweb.uniplace.domain.contract.api.dto.response.ResidentResponse;
import org.myweb.uniplace.domain.contract.application.ContractImageService;
import org.myweb.uniplace.domain.contract.application.ResidentService;
import org.myweb.uniplace.domain.contract.domain.entity.Contract;
import org.myweb.uniplace.domain.contract.domain.entity.Resident;
import org.myweb.uniplace.domain.contract.domain.enums.ContractStatus;
import org.myweb.uniplace.domain.contract.repository.ContractRepository;
import org.myweb.uniplace.domain.contract.repository.ResidentRepository;
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
import org.myweb.uniplace.global.storage.StorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real request and database boundaries; no test-wide transaction may mask a readonly service. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1", "server.servlet.context-path=/api",
        "jwt.secret=resident-transaction-test-secret-with-at-least-32-bytes",
        "jwt.access-exp=3600000", "jwt.refresh-exp=86400000",
        "spring.mail.host=localhost", "spring.mail.port=2525",
        "spring.mail.username=local", "spring.mail.password=local",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.show-sql=false",
        "logging.level.org.hibernate.SQL=WARN", "payment.reconcile.enabled=false",
        "ai.fastapi.base-url=http://127.0.0.1:9", "slack.webhook-url=",
        "contract.status-transition-cron=-", "storage.type=local"
})
@Testcontainers
class ResidentCreationTransactionIntegrationTest {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String PASSWORD_HASH = new BCryptPasswordEncoder()
            .encode("Synthetic-resident-transaction-only-123!");
    private static final Path UPLOAD_ROOT = temporaryRoot();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("resident_creation_transaction").withUsername("resident_tx_test")
            .withPassword("resident-transaction-test-only");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", MYSQL::getJdbcUrl);
        properties.add("spring.datasource.username", MYSQL::getUsername);
        properties.add("spring.datasource.password", MYSQL::getPassword);
        properties.add("file.upload-path", UPLOAD_ROOT::toString);
    }

    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JwtProvider jwt;
    @Autowired ResidentService service;
    @Autowired ResidentRepository residents;
    @Autowired ContractRepository contracts;
    @Autowired BuildingRepository buildings;
    @Autowired RoomRepository rooms;
    @Autowired UserRepository users;
    @MockitoBean StorageService storage;
    @MockitoBean ContractImageService images;
    @MockitoBean NotificationService notifications;
    @MockitoBean SlackNotificationService slack;

    private Fixture fixture;

    @BeforeEach
    void committedSyntheticContractAndActors() {
        fixture = new TransactionTemplate(transactions).execute(status -> {
            Building building = buildings.saveAndFlush(Building.builder()
                    .buildingNm("Resident transaction fixture").buildingAddr("Synthetic fixture address").build());
            Building otherBuilding = buildings.saveAndFlush(Building.builder()
                    .buildingNm("Other resident fixture").buildingAddr("Synthetic other address").build());
            String suffix = String.valueOf(building.getBuildingId());
            User owner = user("resident-owner-" + suffix, UserRole.tenant);
            User other = user("resident-other-" + suffix, UserRole.user);
            User admin = user("resident-admin-" + suffix, UserRole.admin);
            Room room = rooms.saveAndFlush(Room.builder().building(building).roomNo(901).floor(1)
                    .roomSize(new BigDecimal("20.00")).rentPrice(new BigDecimal("500000"))
                    .deposit(new BigDecimal("1000000")).manageFee(new BigDecimal("50000"))
                    .rentMin(1).roomSt(RoomStatus.contracted).build());
            Contract contract = contract(owner, room);
            return new Fixture(owner.getUserId(), other.getUserId(), admin.getUserId(),
                    building.getBuildingId(), otherBuilding.getBuildingId(), room.getRoomId(), contract.getContractId());
        });
        clearInvocations(storage, images, notifications, slack);
    }

    @Test
    void anAdministratorCreatesACommittedResidentAndSequentialRetryReturnsTheSameRow() throws Exception {
        Snapshot before = snapshot();
        JsonNode created = data(request("POST", json.writeValueAsString(validFields()), adminToken()));
        int residentId = created.path("residentId").asInt();
        assertThat(residentId).isPositive();
        assertResidentFields(created, residentId);
        assertThat(residentCount(fixture.contractId())).isEqualTo(1);
        Snapshot afterCreate = snapshot();
        assertThat(afterCreate.residents()).isEqualTo(before.residents() + 1);
        assertBusinessStateUnchanged(before, afterCreate);

        JsonNode repeated = data(request("POST", json.writeValueAsString(validFields()), adminToken()));
        assertResidentFields(repeated, residentId);
        assertThat(snapshot()).isEqualTo(afterCreate);

        JsonNode listed = data(request("GET", null, adminToken()));
        assertThat(listed.isArray()).isTrue();
        List<JsonNode> matches = new ArrayList<>();
        listed.forEach(row -> { if (row.path("residentId").asInt() == residentId) matches.add(row); });
        assertThat(matches).hasSize(1);
        assertResidentFields(matches.get(0), residentId);
        assertNoExternalEffects();
    }

    @Test
    void anAlreadyExistingResidentCanBeReturnedWithoutAnotherInsert() throws Exception {
        int existing = seedResident();
        Snapshot before = snapshot();
        assertResidentFields(data(request("POST", json.writeValueAsString(validFields()), adminToken())), existing);
        assertResidentFields(data(request("POST", json.writeValueAsString(validFields()), adminToken())), existing);
        assertNoMutation(before);
    }

    @Test
    void manualCreationDoesNotActivateARequestedContractPromoteItsMemberOrOccupyItsRoom() throws Exception {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.update("UPDATE contract SET contract_st='requested' WHERE contract_id=?", fixture.contractId());
            jdbc.update("UPDATE users SET user_role='user' WHERE user_id=?", fixture.ownerId());
            jdbc.update("UPDATE rooms SET room_st='available' WHERE room_id=?", fixture.roomId());
        });
        Snapshot before = snapshot();
        assertThat(before.contractState()).isEqualTo("requested");
        assertThat(before.ownerRole()).isEqualTo("user");
        assertThat(before.roomState()).isEqualTo("available");

        JsonNode created = data(request("POST", json.writeValueAsString(validFields()), adminToken()));
        int residentId = created.path("residentId").asInt();
        assertThat(residentId).isPositive();
        assertResidentFields(created, residentId);
        assertThat(residentCount(fixture.contractId())).isEqualTo(1);
        Snapshot after = snapshot();
        assertThat(after.residents()).isEqualTo(before.residents() + 1);
        assertBusinessStateUnchanged(before, after);
        assertNoExternalEffects();
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "building", "blank_user", "existing_user", "existing_building"})
    void mismatchedMappingsAreRejectedEvenWhenAResidentAlreadyExists(String mismatch) throws Exception {
        if (mismatch.startsWith("existing_")) seedResident();
        Map<String, Object> fields = validFields();
        if (mismatch.contains("building")) fields.put("buildingId", fixture.otherBuildingId());
        else fields.put("userId", "blank_user".equals(mismatch) ? "" : fixture.otherId());
        Snapshot before = snapshot();
        assertError(request("POST", json.writeValueAsString(fields), adminToken()), ErrorCode.BAD_REQUEST);
        assertNoMutation(before);
    }

    @Test
    void anUnknownContractKeepsItsExistingNotFoundErrorWithoutCreatingAResident() throws Exception {
        Map<String, Object> fields = validFields();
        fields.put("contractId", Integer.MAX_VALUE);
        Snapshot before = snapshot();
        assertError(request("POST", json.writeValueAsString(fields), adminToken()), ErrorCode.CONTRACT_NOT_FOUND);
        assertNoMutation(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"buildingId", "contractId", "userId"})
    void requiredFieldsAreValidatedBeforeCreation(String field) throws Exception {
        Map<String, Object> fields = validFields();
        fields.remove(field);
        Snapshot before = snapshot();
        assertError(request("POST", json.writeValueAsString(fields), adminToken()), ErrorCode.BAD_REQUEST);
        assertNoMutation(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"malformed_json", "invalid_integer"})
    void malformedRequestBodiesKeepTheExistingBadRequestContract(String kind) throws Exception {
        Map<String, Object> fields = validFields();
        fields.put("contractId", "not-an-integer");
        String body = "malformed_json".equals(kind) ? "{" : json.writeValueAsString(fields);
        Snapshot before = snapshot();
        assertError(request("POST", body, adminToken()), ErrorCode.BAD_REQUEST);
        assertNoMutation(before);
    }

    static Stream<Arguments> deniedActorsAndMethods() {
        return Stream.of("anonymous", "guest", "user", "tenant")
                .flatMap(actor -> Stream.of("GET", "POST").map(method -> Arguments.of(actor, method)));
    }

    @ParameterizedTest
    @MethodSource("deniedActorsAndMethods")
    void residentEndpointsRemainAdministratorOnlyBeforeAnyWork(String actor, String method) throws Exception {
        Snapshot before = snapshot();
        String token = switch (actor) {
            case "anonymous" -> null;
            case "guest" -> jwt.createAccessToken("GUEST_synthetic_resident", "guest");
            case "user" -> jwt.createAccessToken(fixture.otherId(), "user");
            default -> jwt.createAccessToken(fixture.ownerId(), "tenant");
        };
        assertError(request(method, "POST".equals(method) ? json.writeValueAsString(validFields()) : null, token),
                "anonymous".equals(actor) ? ErrorCode.UNAUTHORIZED : ErrorCode.FORBIDDEN);
        assertNoMutation(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST"})
    void aDemotedAdministratorCannotUseTheOldTokenForResidentEndpoints(String method) throws Exception {
        String oldToken = adminToken();
        jdbc.update("UPDATE users SET user_role='user' WHERE user_id=?", fixture.adminId());
        Snapshot before = snapshot();
        assertError(request(method, "POST".equals(method) ? json.writeValueAsString(validFields()) : null, oldToken),
                ErrorCode.FORBIDDEN);
        assertNoMutation(before);
    }

    @Test
    void creationJoinsTheCallersWriteTransactionAndRollsBackItsUncommittedContractAndResident() {
        Snapshot before = snapshot();
        int[] uncommitted = new int[2];
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            Contract contract = contract(users.findById(fixture.ownerId()).orElseThrow(),
                    rooms.findById(fixture.roomId()).orElseThrow());
            uncommitted[0] = contract.getContractId();
            ResidentResponse created = service.createResident(residentRequest(contract.getContractId()));
            uncommitted[1] = created.getResidentId();
            assertThat(created.getContractId()).isEqualTo(contract.getContractId());
            assertThat(created.getBuildingId()).isEqualTo(fixture.buildingId());
            assertThat(created.getUserId()).isEqualTo(fixture.ownerId());
            assertThat(residentCount(contract.getContractId())).isEqualTo(1);
            assertThat(service.createResident(residentRequest(contract.getContractId())).getResidentId())
                    .isEqualTo(created.getResidentId());
            assertInvisibleToAnotherConnection(contract.getContractId());
            status.setRollbackOnly();
        });
        assertThat(uncommitted[0]).isPositive();
        assertThat(uncommitted[1]).isPositive();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM contract WHERE contract_id=?",
                Integer.class, uncommitted[0])).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM residents WHERE resident_id=?",
                Integer.class, uncommitted[1])).isZero();
        assertNoMutation(before);
    }

    @Test
    void anExistingReadOnlyCallerIsNotBypassedWithAnIndependentCommit() {
        Snapshot before = snapshot();
        TransactionTemplate readOnlyCaller = new TransactionTemplate(transactions);
        readOnlyCaller.setReadOnly(true);
        assertThatThrownBy(() -> readOnlyCaller.executeWithoutResult(status ->
                service.createResident(residentRequest(fixture.contractId()))))
                .isInstanceOfAny(DataAccessException.class, TransactionException.class);
        assertThat(residentCount(fixture.contractId())).isZero();
        assertNoMutation(before);
    }

    private void assertInvisibleToAnotherConnection(int contractId) {
        Long currentConnection = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
        assertThat(currentConnection).isNotNull();
        // Raw DataSource acquisition deliberately avoids the caller-bound DataSourceUtils connection.
        try (Connection outside = dataSource.getConnection()) {
            outside.setAutoCommit(true);
            try (PreparedStatement query = outside.prepareStatement("SELECT CONNECTION_ID()");
                    ResultSet result = query.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isNotEqualTo(currentConnection.longValue());
            }
            assertThat(outsideCount(outside, "SELECT COUNT(*) FROM contract WHERE contract_id=?", contractId)).isZero();
            assertThat(outsideCount(outside, "SELECT COUNT(*) FROM residents WHERE contract_id=?", contractId)).isZero();
        } catch (java.sql.SQLException failure) {
            throw new AssertionError("Independent synthetic visibility query failed", failure);
        }
    }

    private static long outsideCount(Connection connection, String sql, int contractId) throws java.sql.SQLException {
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setInt(1, contractId);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) throw new AssertionError("Synthetic count query returned no row");
                return result.getLong(1);
            }
        }
    }

    private Snapshot snapshot() {
        return new Snapshot(count("residents"), count("contract"), count("files"), count("notification"),
                jdbc.queryForObject("SELECT room_st FROM rooms WHERE room_id=?", String.class, fixture.roomId()),
                jdbc.queryForObject("SELECT user_role FROM users WHERE user_id=?", String.class, fixture.ownerId()),
                jdbc.queryForObject("SELECT contract_st FROM contract WHERE contract_id=?", String.class, fixture.contractId()));
    }

    private long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }

    private int residentCount(int contractId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM residents WHERE contract_id=? AND user_id=?",
                Integer.class, contractId, fixture.ownerId());
    }

    private void assertBusinessStateUnchanged(Snapshot before, Snapshot after) {
        assertThat(after.contracts()).isEqualTo(before.contracts());
        assertThat(after.files()).isEqualTo(before.files());
        assertThat(after.notifications()).isEqualTo(before.notifications());
        assertThat(after.roomState()).isEqualTo(before.roomState());
        assertThat(after.ownerRole()).isEqualTo(before.ownerRole());
        assertThat(after.contractState()).isEqualTo(before.contractState());
    }

    private void assertNoMutation(Snapshot before) {
        assertThat(snapshot()).isEqualTo(before);
        assertNoExternalEffects();
    }

    private void assertNoExternalEffects() { verifyNoInteractions(storage, images, notifications, slack); }

    private void assertResidentFields(JsonNode node, int residentId) {
        assertThat(node.size()).isEqualTo(4);
        assertThat(node.path("residentId").asInt()).isEqualTo(residentId);
        assertThat(node.path("buildingId").asInt()).isEqualTo(fixture.buildingId());
        assertThat(node.path("contractId").asInt()).isEqualTo(fixture.contractId());
        assertThat(node.path("userId").asText()).isEqualTo(fixture.ownerId());
    }

    private Map<String, Object> validFields() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("buildingId", fixture.buildingId());
        result.put("contractId", fixture.contractId());
        result.put("userId", fixture.ownerId());
        return result;
    }

    private ResidentCreateRequest residentRequest(int contractId) {
        return ResidentCreateRequest.builder().buildingId(fixture.buildingId())
                .contractId(contractId).userId(fixture.ownerId()).build();
    }

    private int seedResident() {
        return new TransactionTemplate(transactions).execute(status -> residents.saveAndFlush(Resident.builder()
                .buildingId(fixture.buildingId()).contractId(fixture.contractId()).userId(fixture.ownerId()).build())
                .getResidentId());
    }

    private User user(String id, UserRole role) {
        return users.saveAndFlush(User.builder().userId(id).userNm("Synthetic resident actor")
                .userEmail(id + "@example.test").userPwd(PASSWORD_HASH).userBirth(LocalDate.of(2000, 1, 1))
                .userTel("010-0000-0000").userNickname(id).userRole(role).firstSign("N").build());
    }

    private Contract contract(User owner, Room room) {
        LocalDate today = LocalDate.now(KST);
        return contracts.saveAndFlush(Contract.builder().user(owner).room(room)
                .contractStart(today.minusDays(7)).contractEnd(today.plusMonths(1))
                .deposit(new BigDecimal("1000000")).rentPrice(new BigDecimal("500000"))
                .manageFee(new BigDecimal("50000")).paymentDay(15).contractSt(ContractStatus.active)
                .lessorNm("Synthetic resident").lessorTel("010-0000-0000")
                .lessorAddr("Synthetic resident fixture address").lessorRrn("000000-0000000").build());
    }

    private String adminToken() { return jwt.createAccessToken(fixture.adminId(), "admin"); }

    private Reply request(String method, String body, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/admin/residents"))
                .timeout(Duration.ofSeconds(15)).header("Accept", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.header("Content-Type", "application/json");
        HttpResponse<byte[]> reply = HTTP.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        return new Reply(reply.statusCode(), reply.body());
    }

    private JsonNode data(Reply response) throws Exception {
        assertThat(response.status()).isEqualTo(200);
        JsonNode parsed = json.readTree(response.body());
        assertThat(parsed.path("success").asBoolean()).isTrue();
        return parsed.path("data");
    }

    private void assertError(Reply response, ErrorCode expected) throws Exception {
        assertThat(response.status()).isEqualTo(expected.getStatus().value());
        JsonNode parsed = json.readTree(response.body());
        assertThat(parsed.path("success").asBoolean()).isFalse();
        assertThat(parsed.path("errorCode").asText()).isEqualTo(expected.getCode());
    }

    @AfterAll
    static void removeOnlySyntheticUploadRoot() throws Exception {
        try (Stream<Path> paths = Files.walk(UPLOAD_ROOT)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static Path temporaryRoot() {
        try { return Files.createTempDirectory("uniplace-resident-transaction-"); }
        catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }

    private record Fixture(String ownerId, String otherId, String adminId, int buildingId, int otherBuildingId,
            int roomId, int contractId) {}
    private record Snapshot(long residents, long contracts, long files, long notifications,
            String roomState, String ownerRole, String contractState) {}
    private record Reply(int status, byte[] body) {}
}
