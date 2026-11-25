package org.myweb.uniplace.domain.contract.api;

import static org.myweb.uniplace.support.TestOwnershipFixtures.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.uniplace.domain.contract.api.dto.request.ContractCreateRequest;
import org.myweb.uniplace.domain.contract.application.ContractImageService;
import org.myweb.uniplace.domain.contract.application.ContractService;
import org.myweb.uniplace.domain.contract.domain.entity.Contract;
import org.myweb.uniplace.domain.contract.domain.enums.ContractStatus;
import org.myweb.uniplace.domain.contract.repository.ContractRepository;
import org.myweb.uniplace.domain.file.domain.entity.UploadFile;
import org.myweb.uniplace.domain.file.repository.UploadFileRepository;
import org.myweb.uniplace.domain.notification.application.NotificationService;
import org.myweb.uniplace.domain.notification.domain.enums.TargetType;
import org.myweb.uniplace.domain.property.domain.entity.Building;
import org.myweb.uniplace.domain.property.domain.entity.Room;
import org.myweb.uniplace.domain.property.domain.enums.RoomStatus;
import org.myweb.uniplace.domain.property.repository.BuildingRepository;
import org.myweb.uniplace.domain.property.repository.RoomRepository;
import org.myweb.uniplace.domain.user.domain.entity.User;
import org.myweb.uniplace.domain.user.domain.enums.UserRole;
import org.myweb.uniplace.domain.user.repository.UserRepository;
import org.myweb.uniplace.global.exception.BusinessException;
import org.myweb.uniplace.global.exception.ErrorCode;
import org.myweb.uniplace.global.security.JwtProvider;
import org.myweb.uniplace.global.slack.SlackNotificationService;
import org.myweb.uniplace.global.storage.StorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real create and file services; all storage bytes and delivery effects are synthetic. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1", "server.servlet.context-path=/api",
        "jwt.secret=contract-create-test-secret-with-at-least-32-bytes",
        "jwt.access-exp=3600000", "jwt.refresh-exp=86400000",
        "spring.mail.host=localhost", "spring.mail.port=2525",
        "spring.mail.username=local", "spring.mail.password=local",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.show-sql=false",
        "logging.level.org.hibernate.SQL=WARN", "payment.reconcile.enabled=false",
        "ai.fastapi.base-url=http://127.0.0.1:9", "slack.webhook-url=",
        "contract.status-transition-cron=-", "storage.type=local"
})
@Testcontainers
class ContractCreateValidationIntegrationTest {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String BROWSER_PASSWORD = "Synthetic-contract-create-browser-only-123!";
    private static final String PASSWORD_HASH = new BCryptPasswordEncoder().encode(BROWSER_PASSWORD);
    private static final byte[] SIGNATURE_PNG = png("SYNTHETIC SIGNATURE", new Color(227, 242, 250));
    private static final byte[] DOCUMENT_PNG = png("SYNTHETIC CONTRACT DOCUMENT", new Color(250, 238, 221));
    private static final Path UPLOAD_ROOT = temporaryRoot();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("contract_create_validation").withUsername("contract_create_test")
            .withPassword("contract-create-test-only");

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
    @Autowired JwtProvider jwt;
    @Autowired ContractService service;
    @Autowired ContractRepository contracts;
    @Autowired UploadFileRepository files;
    @Autowired UserRepository users;
    @Autowired RoomRepository rooms;
    @Autowired BuildingRepository buildings;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean StorageService storage;
    @MockitoBean ContractImageService images;
    @MockitoBean NotificationService notifications;
    @MockitoBean SlackNotificationService slack;

    private Fixture fixture;

    @BeforeEach
    void committedSyntheticRoomAndMember() throws Exception {
        configureSyntheticEffects(storage, images, files);
        fixture = new TransactionTemplate(transactions).execute(status -> {
            Building building = building(buildings);
            User owner = user(users, "create-test-" + building.getBuildingId());
            Room room = room(rooms, building);
            User operator = user(users, "create-operator-" + building.getBuildingId());
            grantRentalOwner(jdbc, operator.getUserId(), building.getBuildingId(), room.getRoomId(), null);
            LocalDate start = LocalDate.now(KST).plusDays(2);
            return new Fixture(owner.getUserId(), room.getRoomId(), start, start.plusMonths(1));
        });
        clearInvocations(storage, images, notifications, slack);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "32", "2147483647", "-2147483648"})
    void outOfRangePaymentDaysFailBeforeCreatingRowsOrExternalEffects(String day) throws Exception {
        Snapshot before = snapshot();
        assertError(post(form(day, SIGNATURE_PNG, "synthetic-signature.png"), ownerToken()), ErrorCode.BAD_REQUEST);
        assertNoEffects(before);
    }

    static Stream<Arguments> missingOrMalformedDays() {
        return Stream.of(Arguments.of((String) null), Arguments.of(""), Arguments.of("abc"),
                Arguments.of("1.5"), Arguments.of("2147483648"));
    }

    @ParameterizedTest
    @MethodSource("missingOrMalformedDays")
    void missingOrMalformedPaymentDayKeepsTheExistingBindingError(String day) throws Exception {
        Snapshot before = snapshot();
        assertError(post(form(day, SIGNATURE_PNG, "synthetic-signature.png"), ownerToken()), ErrorCode.BAD_REQUEST);
        assertNoEffects(before);
    }

    static Stream<Arguments> absentOrEmptySignatures() {
        return Stream.of(Arguments.of((byte[]) null, "synthetic-signature.png"),
                Arguments.of(new byte[0], "synthetic-signature.png"), Arguments.of(new byte[0], ""));
    }

    @ParameterizedTest
    @MethodSource("absentOrEmptySignatures")
    void missingOrZeroByteSignatureCannotCreateAnUnsignedContract(byte[] signature, String filename) throws Exception {
        Snapshot before = snapshot();
        assertError(post(form("15", signature, filename), ownerToken()), ErrorCode.BAD_REQUEST);
        assertNoEffects(before);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 31})
    void paymentDayBoundariesCreateOneOwnedRequestedContractWithItsSignatureAndProxyDocument(int day) throws Exception {
        Snapshot before = snapshot();
        JsonNode result = data(post(form(String.valueOf(day), SIGNATURE_PNG, "synthetic-signature.png"), ownerToken()));
        assertSuccessfulCreate(result, day, before);
    }

    static Stream<Arguments> invalidDirectDays() {
        return Stream.of(Arguments.of((Integer) null), Arguments.of(0), Arguments.of(-1),
                Arguments.of(32), Arguments.of(Integer.MAX_VALUE), Arguments.of(Integer.MIN_VALUE));
    }

    @ParameterizedTest
    @MethodSource("invalidDirectDays")
    void directCreateAlsoRejectsInvalidDaysBeforeLookingUpTheRoom(Integer day) {
        Snapshot before = snapshot();
        ContractCreateRequest request = directRequest(day, signature());
        request.setRoomId(Integer.MAX_VALUE);
        withOwner(() -> assertBusiness(() -> service.createContract(request), ErrorCode.BAD_REQUEST));
        assertNoEffects(before);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void directCreateAlsoRequiresANonemptySignatureBeforeLookingUpTheRoom(boolean empty) {
        Snapshot before = snapshot();
        MultipartFile signature = empty
                ? new MockMultipartFile("signFile", "synthetic-signature.png", "image/png", new byte[0]) : null;
        ContractCreateRequest request = directRequest(15, signature);
        request.setRoomId(Integer.MAX_VALUE);
        withOwner(() -> assertBusiness(() -> service.createContract(request), ErrorCode.BAD_REQUEST));
        assertNoEffects(before);
    }

    @Test
    void anAuthenticatedDirectCallerCannotPassANullRequest() {
        Snapshot before = snapshot();
        withOwner(() -> assertBusiness(() -> service.createContract(null), ErrorCode.BAD_REQUEST));
        assertNoEffects(before);
    }

    @Test
    void directInputValidationPrecedesUserAndRoomExistenceLookups() {
        Snapshot before = snapshot();
        withToken(jwt.createAccessToken("missing-synthetic-create-member", "user"), () ->
                assertBusiness(() -> service.createContract(directRequest(0, signature())), ErrorCode.BAD_REQUEST));
        assertNoEffects(before);
    }

    @Test
    void directCreateStillRequiresAuthenticationBeforeReadingInvalidInput() {
        Snapshot before = snapshot();
        withToken(null, () -> assertBusiness(() -> service.createContract(null), ErrorCode.UNAUTHORIZED));
        assertNoEffects(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"anonymous", "guest", "expired", "refresh"})
    void validInputKeepsTheExistingAuthenticationErrorsWithoutEffects(String actor) throws Exception {
        Snapshot before = snapshot();
        String token = switch (actor) {
            case "anonymous" -> null;
            case "guest" -> jwt.createAccessToken("GUEST_synthetic_create", "guest");
            case "expired" -> jwt.createAccessToken(fixture.ownerId(), "user", -60_000);
            case "refresh" -> jwt.createRefreshToken(fixture.ownerId());
            default -> throw new IllegalArgumentException("Unexpected synthetic actor");
        };
        ErrorCode expected = switch (actor) {
            case "anonymous" -> ErrorCode.UNAUTHORIZED;
            case "guest" -> ErrorCode.FORBIDDEN;
            case "expired" -> ErrorCode.TOKEN_EXPIRED;
            default -> ErrorCode.TOKEN_TYPE_INVALID;
        };
        assertError(post(form("15", SIGNATURE_PNG, "synthetic-signature.png"), token), expected);
        assertNoEffects(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"short", "reversed", "past", "missing-start", "missing-end"})
    void validPaymentAndSignatureKeepTheExistingPeriodErrors(String kind) throws Exception {
        Snapshot before = snapshot();
        LocalDate start = fixture.start();
        LocalDate end = fixture.end();
        if ("short".equals(kind)) end = start.plusDays(6);
        if ("reversed".equals(kind)) end = start.minusDays(1);
        if ("past".equals(kind)) start = LocalDate.now(KST).minusDays(1);
        if ("missing-start".equals(kind)) start = null;
        if ("missing-end".equals(kind)) end = null;
        ErrorCode expected = List.of("short", "reversed").contains(kind)
                ? ErrorCode.CONTRACT_INVALID_PERIOD : ErrorCode.BAD_REQUEST;
        assertError(post(form("15", SIGNATURE_PNG, "synthetic-signature.png", start, end), ownerToken()), expected);
        assertNoEffects(before);
    }

    @Test
    void validInputStillRejectsAnOverlappingContractWithoutCreatingAnotherOne() throws Exception {
        new TransactionTemplate(transactions).executeWithoutResult(status -> contracts.saveAndFlush(Contract.builder()
                .user(users.findById(fixture.ownerId()).orElseThrow())
                .room(rooms.findById(fixture.roomId()).orElseThrow())
                .contractStart(fixture.start()).contractEnd(fixture.end()).paymentDay(15)
                .rentPrice(new BigDecimal("500000")).contractSt(ContractStatus.requested)
                .lessorNm("Synthetic tenant").lessorTel("010-0000-0000")
                .lessorAddr("Synthetic fixture address").lessorRrn("000000-0000000").build()));
        Snapshot before = snapshot();
        assertError(post(form("15", SIGNATURE_PNG, "synthetic-signature.png"), ownerToken()), ErrorCode.CONTRACT_OVERLAP);
        assertNoEffects(before);
    }

    @Test
    void anEmptySignatureFailureCanBeCorrectedWithoutAHiddenContractOrDuplicateEffects() throws Exception {
        Snapshot before = snapshot();
        assertError(post(form("31", new byte[0], "synthetic-signature.png"), ownerToken()), ErrorCode.BAD_REQUEST);
        assertNoEffects(before);
        JsonNode result = data(post(form("31", SIGNATURE_PNG, "synthetic-signature.png"), ownerToken()));
        assertSuccessfulCreate(result, 31, before);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void contractSlackFollowsTheOutermostTransaction(boolean commit) {
        Snapshot before = snapshot();
        withOwner(() -> {
            int contractId = new TransactionTemplate(transactions).execute(status -> {
                int id = service.createContract(directRequest(15, signature())).getContractId();
                verifyNoInteractions(slack);
                if (!commit) status.setRollbackOnly();
                return id;
            });
            if (commit) {
                assertThat(contracts.existsById(contractId)).isTrue();
                verify(slack, times(1)).send(contains("계약 ID : #" + contractId));
                verifyNoMoreInteractions(slack);
            } else {
                assertThat(snapshot()).isEqualTo(before);
                verifyNoInteractions(slack);
            }
        });
    }

    @Test
    void signatureStorageFailureRollsBackTheContractWithoutSendingSlack() throws Exception {
        Snapshot before = snapshot();
        doThrow(new IOException("Synthetic storage failure")).when(storage)
                .store(any(MultipartFile.class), anyString(), anyString());
        assertError(post(form("15", SIGNATURE_PNG, "synthetic-signature.png"), ownerToken()), ErrorCode.INTERNAL_ERROR);
        assertThat(snapshot()).isEqualTo(before);
        verifyNoInteractions(images, slack);
    }

    @Test
    void slackDeliveryFailureDoesNotFailACommittedApplication() throws Exception {
        Snapshot before = snapshot();
        doThrow(new IllegalStateException("Synthetic Slack delivery failure")).when(slack).send(anyString());
        JsonNode result = data(post(form("15", SIGNATURE_PNG, "synthetic-signature.png"), ownerToken()));
        assertSuccessfulCreate(result, 15, before);
    }

    private void assertSuccessfulCreate(JsonNode result, int day, Snapshot before) throws Exception {
        int contractId = result.path("contractId").asInt();
        int documentId = result.path("contractPdfFileId").asInt();
        assertThat(contractId).isPositive();
        assertThat(documentId).isPositive();
        assertThat(result.path("roomId").asInt()).isEqualTo(fixture.roomId());
        assertThat(result.path("paymentDay").asInt()).isEqualTo(day);
        assertThat(result.path("contractStatus").asText()).isEqualTo("requested");
        assertThat(result.path("contractPdfUrl").asText()).isEqualTo("/files/" + documentId + "/download");
        assertThat(result.path("deposit").decimalValue()).isEqualByComparingTo("1000000");
        assertThat(result.path("rentPrice").decimalValue()).isEqualByComparingTo("500000");
        assertThat(result.path("manageFee").decimalValue()).isEqualByComparingTo("50000");
        assertThat(jdbc.queryForObject("SELECT user_id FROM contract WHERE contract_id=?", String.class, contractId))
                .isEqualTo(fixture.ownerId());
        assertThat(jdbc.queryForObject("SELECT payment_day FROM contract WHERE contract_id=?", Integer.class, contractId))
                .isEqualTo(day);
        int signatureId = jdbc.queryForObject("SELECT lessor_sign_file_id FROM contract WHERE contract_id=?",
                Integer.class, contractId);
        UploadFile signature = files.findById(signatureId).orElseThrow();
        assertThat(signature.getFileParentType()).isEqualTo("CONTRACT");
        assertThat(signature.getFileParentId()).isEqualTo(contractId);
        assertThat(signature.getOriginFilename()).isEqualTo("synthetic-signature.png");
        assertThat(signature.getFileSize()).isEqualTo(SIGNATURE_PNG.length);
        assertThat(files.findById(documentId).orElseThrow().getFileParentId()).isEqualTo(contractId);
        Snapshot after = snapshot();
        assertThat(after.contracts()).isEqualTo(before.contracts() + 1);
        assertThat(after.files()).isEqualTo(before.files() + 2);
        assertThat(after.residents()).isEqualTo(before.residents());
        assertThat(after.notifications()).isEqualTo(before.notifications());
        assertThat(after.roomState()).isEqualTo(before.roomState());
        assertThat(after.role()).isEqualTo(before.role());
        verify(storage, times(1)).store(any(MultipartFile.class), eq(signature.getFilePath()), eq(signature.getRenameFilename()));
        verify(images, times(1)).generateAndSave(any(Contract.class));
        verify(notifications, times(1)).notifyUser(eq("create-operator-" + jdbc.queryForObject(
                "SELECT building_id FROM rooms WHERE room_id=?", Integer.class, fixture.roomId())),
                eq("CONTRACT_REQ"), anyString(), isNull(), eq(TargetType.contract), eq(contractId),
                eq("/owner/contracts?contractId=" + contractId));
        verify(slack, times(1)).send(anyString());
        verifyNoMoreInteractions(storage, images, notifications, slack);
    }

    private Snapshot snapshot() {
        return new Snapshot(count("contract"), count("files"), count("residents"), count("notification"),
                jdbc.queryForObject("SELECT room_st FROM rooms WHERE room_id=?", String.class, fixture.roomId()),
                jdbc.queryForObject("SELECT user_role FROM users WHERE user_id=?", String.class, fixture.ownerId()));
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private void assertNoEffects(Snapshot before) {
        assertThat(snapshot()).isEqualTo(before);
        verifyNoInteractions(storage, images, notifications, slack);
    }

    private ContractCreateRequest directRequest(Integer day, MultipartFile signature) {
        return ContractCreateRequest.builder().roomId(fixture.roomId()).contractStart(fixture.start())
                .contractEnd(fixture.end()).paymentDay(day).lessorNm("Synthetic tenant")
                .lessorTel("010-0000-0000").lessorAddr("Synthetic fixture address")
                .lessorRrn("000000-0000000").signFile(signature).build();
    }

    private static MockMultipartFile signature() {
        return new MockMultipartFile("signFile", "synthetic-signature.png", "image/png", SIGNATURE_PNG);
    }

    private void withOwner(Runnable action) { withToken(ownerToken(), action); }

    private void withToken(String token, Runnable action) {
        SecurityContextHolder.clearContext();
        try {
            if (token != null) SecurityContextHolder.getContext().setAuthentication(jwt.getAuthentication(token));
            action.run();
        } finally { SecurityContextHolder.clearContext(); }
    }

    private String ownerToken() { return jwt.createAccessToken(fixture.ownerId(), "user"); }

    private Multipart form(String day, byte[] signature, String filename) throws IOException {
        return form(day, signature, filename, fixture.start(), fixture.end());
    }

    private Multipart form(String day, byte[] signature, String filename, LocalDate start, LocalDate end) throws IOException {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("roomId", String.valueOf(fixture.roomId()));
        if (day != null) fields.put("paymentDay", day);
        if (start != null) fields.put("contractStart", start.toString());
        if (end != null) fields.put("contractEnd", end.toString());
        fields.put("lessorNm", "Synthetic tenant"); fields.put("lessorTel", "010-0000-0000");
        fields.put("lessorAddr", "Synthetic fixture address"); fields.put("lessorRrn", "000000-0000000");
        String boundary = "SyntheticCreateBoundary" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (var field : fields.entrySet()) bytes.write(("--" + boundary
                + "\r\nContent-Disposition: form-data; name=\"" + field.getKey() + "\"\r\n\r\n"
                + field.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
        if (signature != null) {
            bytes.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"signFile\"; filename=\""
                    + filename + "\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            bytes.write(signature); bytes.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        bytes.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return new Multipart(bytes.toByteArray(), "multipart/form-data; boundary=" + boundary);
    }

    private Reply post(Multipart multipart, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/contracts"))
                .timeout(Duration.ofSeconds(15)).header("Accept", "application/json")
                .header("Content-Type", multipart.contentType());
        if (token != null) request.header("Authorization", "Bearer " + token);
        HttpResponse<byte[]> response = HTTP.send(request.POST(HttpRequest.BodyPublishers.ofByteArray(multipart.body()))
                .build(), HttpResponse.BodyHandlers.ofByteArray());
        return new Reply(response.statusCode(), response.body());
    }

    private JsonNode data(Reply response) throws IOException {
        assertThat(response.status()).isEqualTo(200);
        JsonNode parsed = json.readTree(response.body());
        assertThat(parsed.path("success").asBoolean()).isTrue();
        assertThat(parsed.path("data").isObject()).isTrue();
        return parsed.path("data");
    }

    private void assertError(Reply response, ErrorCode expected) throws IOException {
        assertThat(response.status()).isEqualTo(expected.getStatus().value());
        JsonNode parsed = json.readTree(response.body());
        assertThat(parsed.path("success").asBoolean()).isFalse();
        assertThat(parsed.path("errorCode").asText()).isEqualTo(expected.getCode());
    }

    private static void assertBusiness(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(code));
    }

    /** Normal CI has neither an extra browser test nor a skip; only explicit local runs opt in. */
    @AfterAll
    static void optionalBrowserFixture(@Autowired UserRepository users, @Autowired BuildingRepository buildings,
            @Autowired RoomRepository rooms, @Autowired UploadFileRepository files, @Autowired StorageService storage,
            @Autowired ContractImageService images, @Autowired PlatformTransactionManager transactions,
            @Autowired ObjectMapper json, @Autowired JdbcTemplate jdbc, @LocalServerPort int port) throws Exception {
        try {
            String target = System.getenv("UNIPLACE_CONTRACT_CREATE_BROWSER_READY");
            if (target == null || target.isBlank()) return;
            configureSyntheticEffects(storage, images, files);
            Map<String, Object> value = new TransactionTemplate(transactions).execute(status -> {
                User owner = user(users, "create-browser-owner");
                Building building = building(buildings);
                Room room = room(rooms, building);
                User operator = user(users, "create-browser-operator");
                grantRentalOwner(jdbc, operator.getUserId(), building.getBuildingId(), room.getRoomId(), null);
                return Map.<String, Object>of("baseUrl", "http://127.0.0.1:" + port + "/api",
                        "ownerId", owner.getUserId(), "roomId", room.getRoomId(), "buildingId", building.getBuildingId());
            });
            Path ready = Path.of(target).toAbsolutePath();
            Path done = Path.of(ready + ".done");
            if (Files.exists(done, LinkOption.NOFOLLOW_LINKS)) throw new AssertionError("Browser marker already exists");
            Files.createDirectories(ready.getParent());
            Files.writeString(ready, json.writeValueAsString(value), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
            while (!Files.exists(done)) {
                if (System.nanoTime() >= deadline) throw new AssertionError("Browser verification timed out");
                Thread.sleep(200);
            }
            assertThat(Files.readString(done)).isEqualTo("passed");
        } finally {
            try (Stream<Path> paths = Files.walk(UPLOAD_ROOT)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void configureSyntheticEffects(StorageService storage, ContractImageService images,
            UploadFileRepository files) throws IOException {
        when(storage.read(anyString(), anyString())).thenAnswer(invocation -> new ByteArrayInputStream(
                "synthetic-document.png".equals(invocation.getArgument(1)) ? DOCUMENT_PNG : SIGNATURE_PNG));
        when(images.generateAndSave(any(Contract.class))).thenAnswer(invocation -> {
            Contract contract = invocation.getArgument(0);
            return files.saveAndFlush(UploadFile.builder().fileParentType("CONTRACT").fileParentId(contract.getContractId())
                    .filePath("CONTRACT/" + contract.getContractId() + "/synthetic")
                    .originFilename("synthetic-document.png").renameFilename("synthetic-document.png")
                    .fileSize(DOCUMENT_PNG.length).fileType(".png").sortOrder(1).build()).getFileId();
        });
    }

    private static Building building(BuildingRepository buildings) {
        return buildings.saveAndFlush(Building.builder().buildingNm("Synthetic create fixture")
                .buildingAddr("Synthetic fixture address").build());
    }

    private static User user(UserRepository users, String id) {
        return users.saveAndFlush(User.builder().userId(id).userNm("Synthetic tenant").userEmail(id + "@example.test")
                .userPwd(PASSWORD_HASH).userBirth(LocalDate.of(2000, 1, 1)).userTel("010-0000-0000")
                .userNickname(id).userRole(UserRole.user).firstSign("N").build());
    }

    private static Room room(RoomRepository rooms, Building building) {
        return rooms.saveAndFlush(Room.builder().building(building).roomNo(901).floor(1).roomSize(new BigDecimal("20.00"))
                .rentPrice(new BigDecimal("500000")).deposit(new BigDecimal("1000000"))
                .manageFee(new BigDecimal("50000")).rentMin(1).roomSt(RoomStatus.available).build());
    }

    private static Path temporaryRoot() {
        try { return Files.createTempDirectory("uniplace-contract-create-"); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
    }

    private static byte[] png(String label, Color background) {
        try {
            BufferedImage image = new BufferedImage(760, 280, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            try {
                graphics.setColor(background); graphics.fillRect(0, 0, 760, 280); graphics.setColor(Color.BLACK);
                graphics.drawString(label, 30, 85); graphics.drawString("Generated fixture. No customer data.", 30, 125);
            } finally { graphics.dispose(); }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", bytes)) throw new IOException("PNG writer unavailable");
            return bytes.toByteArray();
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }

    private record Fixture(String ownerId, int roomId, LocalDate start, LocalDate end) {}
    private record Snapshot(long contracts, long files, long residents, long notifications, String roomState, String role) {}
    private record Multipart(byte[] body, String contentType) {}
    private record Reply(int status, byte[] body) {}
}
