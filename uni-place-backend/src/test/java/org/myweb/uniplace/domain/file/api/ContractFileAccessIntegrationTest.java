package org.myweb.uniplace.domain.file.api;

import static org.myweb.uniplace.support.TestOwnershipFixtures.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.uniplace.domain.ai.application.AiIntentRouter;
import org.myweb.uniplace.domain.ai.application.gateway.AiGateway;
import org.myweb.uniplace.domain.ai.application.gateway.dto.AiGatewayRequest;
import org.myweb.uniplace.domain.ai.application.gateway.dto.AiGatewayResponse;
import org.myweb.uniplace.domain.ai.domain.AiIntent;
import org.myweb.uniplace.domain.contract.api.dto.response.AdminContractSummaryResponse;
import org.myweb.uniplace.domain.contract.api.dto.response.ContractResponse;
import org.myweb.uniplace.domain.contract.domain.entity.Contract;
import org.myweb.uniplace.domain.contract.domain.enums.ContractStatus;
import org.myweb.uniplace.domain.contract.repository.ContractRepository;
import org.myweb.uniplace.domain.file.api.dto.request.FileUploadRequest;
import org.myweb.uniplace.domain.file.api.dto.response.FileResponse;
import org.myweb.uniplace.domain.file.application.FileService;
import org.myweb.uniplace.domain.file.domain.entity.UploadFile;
import org.myweb.uniplace.domain.file.repository.UploadFileRepository;
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
import org.myweb.uniplace.global.storage.StorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** No real customer data or storage: real file authorization is checked before synthetic storage effects. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1", "server.servlet.context-path=/api",
        "jwt.secret=contract-file-test-secret-with-at-least-32-bytes",
        "jwt.access-exp=3600000", "jwt.refresh-exp=86400000",
        "spring.mail.host=localhost", "spring.mail.port=2525",
        "spring.mail.username=local", "spring.mail.password=local",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.show-sql=false",
        "logging.level.org.hibernate.SQL=WARN", "payment.reconcile.enabled=false",
        "ai.fastapi.base-url=http://127.0.0.1:9",
        "ai.internal.token=" + ContractFileAccessIntegrationTest.AI_INTERNAL_TOKEN, "slack.webhook-url=",
        "contract.status-transition-cron=-", "storage.type=local"
})
@Testcontainers
class ContractFileAccessIntegrationTest {
    static final String AI_INTERNAL_TOKEN = "synthetic-ai-file-test-token";
    private static final String AI_DOCUMENT_NAME = "synthetic-purchase-order.xlsx";
    private static final String XLSX_MEDIA_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final byte[] AI_DOCUMENT_BYTES = "Synthetic AI document content".getBytes(StandardCharsets.UTF_8);
    private static final String BROWSER_PASSWORD = "Synthetic-contract-browser-only-123!";
    private static final String PASSWORD_HASH = new BCryptPasswordEncoder().encode(BROWSER_PASSWORD);
    private static final byte[] PRIVATE_PNG = png("PRIVATE SYNTHETIC CONTRACT", new Color(250, 238, 221));
    private static final byte[] PUBLIC_PNG = png("PUBLIC SYNTHETIC ROOM", new Color(224, 240, 250));
    private static final Path STATIC_ROOT = temporaryRoot();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("contract_file_access").withUsername("contract_file_test")
            .withPassword("contract-file-test-only");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", MYSQL::getJdbcUrl);
        properties.add("spring.datasource.username", MYSQL::getUsername);
        properties.add("spring.datasource.password", MYSQL::getPassword);
        // The real static resource handler must never inspect retained application uploads.
        properties.add("file.upload-path", STATIC_ROOT::toString);
    }

    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtProvider jwt;
    @Autowired FileService service;
    @Autowired UploadFileRepository files;
    @Autowired ContractRepository contracts;
    @Autowired UserRepository users;
    @Autowired BuildingRepository buildings;
    @Autowired RoomRepository rooms;
    @Autowired PlatformTransactionManager transactions;
    @Autowired AiIntentRouter aiIntents;
    @MockitoBean StorageService storage;
    @MockitoBean AiGateway aiGateway;

    private Fixture fixture;

    @BeforeEach
    void committedSyntheticFiles() throws Exception {
        configureStorage(storage, true);
        fixture = new TransactionTemplate(transactions).execute(status -> {
            Building building = buildings.saveAndFlush(Building.builder().buildingNm("Contract file fixture")
                    .buildingAddr("Synthetic fixture address").build());
            String prefix = "cf-" + building.getBuildingId();
            User owner = user(users, prefix + "-owner", UserRole.tenant, PASSWORD_HASH);
            User other = user(users, prefix + "-other", UserRole.user, PASSWORD_HASH);
            User admin = user(users, prefix + "-admin", UserRole.admin, PASSWORD_HASH);
            Room room = room(rooms, building, 101);
            Contract own = contract(contracts, owner, room);
            Contract foreign = contract(contracts, other, room);
            User operator = user(users, prefix + "-operator", UserRole.user, PASSWORD_HASH);
            grantRentalOwner(jdbc, operator.getUserId(), building.getBuildingId(), room.getRoomId(), own.getContractId());
            contractParty(jdbc, operator.getUserId(), foreign.getContractId());
            UploadFile document = file(files, "CONTRACT", own.getContractId(), "contract-document.png");
            UploadFile signature = file(files, "CONTRACT", own.getContractId(), "contract-signature.png");
            UploadFile deleted = file(files, "CONTRACT", own.getContractId(), "contract-deleted.png");
            deleted.softDelete();
            files.flush();
            UploadFile otherDocument = file(files, "CONTRACT", foreign.getContractId(), "other-contract.png");
            UploadFile publicImage = file(files, "ROOM", room.getRoomId(), "public-room.png");
            UploadFile orphan = file(files, "CONTRACT", Integer.MAX_VALUE, "orphan-contract.png");
            own.setContractPdfFileId(document.getFileId());
            own.setLessorSignFileId(signature.getFileId());
            contracts.flush();
            return new Fixture(owner.getUserId(), other.getUserId(), admin.getUserId(), own.getContractId(),
                    foreign.getContractId(), room.getRoomId(), document.getFileId(), signature.getFileId(),
                    deleted.getFileId(), otherDocument.getFileId(), publicImage.getFileId(), orphan.getFileId(),
                    document.getFilePath(), document.getRenameFilename(), operator.getUserId());
        });
        writeStaticPrivateFiles(fixture.contractId(), fixture.documentName());
        clearInvocations(storage);
    }

    static Stream<Arguments> actorsAndReadEndpoints() {
        return Stream.of("anonymous", "guest", "other", "owner", "operator", "admin")
                .flatMap(actor -> Stream.of("metadata", "list", "view", "download")
                        .map(endpoint -> Arguments.of(actor, endpoint)));
    }

    @ParameterizedTest
    @MethodSource("actorsAndReadEndpoints")
    void ordinaryContractReadsRequireTheirConsumerOrApprovedSnapshotOwner(String actor, String endpoint) throws Exception {
        Reply reply = request("GET", readPath(endpoint, false), token(actor), null, null);
        if (List.of("owner", "operator").contains(actor)) {
            assertThat(reply.status()).isEqualTo(200);
            assertNoStore(reply);
            if ("view".equals(endpoint) || "download".equals(endpoint)) {
                assertThat(Arrays.equals(reply.body(), PRIVATE_PNG)).isTrue();
                assertThat(reply.header("content-type")).contains("image/png");
                assertThat(reply.header("content-disposition"))
                        .startsWith("view".equals(endpoint) ? "inline" : "attachment");
                verify(storage, times(1)).read(fixture.documentPath(), fixture.documentName());
            } else {
                JsonNode data = data(reply);
                if ("list".equals(endpoint)) {
                    assertThat(data.size()).isEqualTo(2);
                    data.forEach(this::assertPrivateProxyUrls);
                } else {
                    assertPrivateProxyUrls(data);
                }
                verify(storage, never()).read(anyString(), anyString());
                verify(storage, never()).resolveViewUrl(anyInt(), anyString(), anyString());
                verify(storage, never()).resolveDownloadUrl(anyInt(), anyString(), anyString());
            }
        } else {
            assertDenied(reply, actor);
            verifyNoInteractions(storage);
        }
    }

    @ParameterizedTest
    @MethodSource("actorsAndReadEndpoints")
    void legacyAdministratorRoutesDenyEveryActorIncludingStaff(String actor, String endpoint) throws Exception {
        Reply reply = request("GET", readPath(endpoint, true), token(actor), null, null);
        assertDenied(reply, actor);
        verifyNoInteractions(storage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"anonymous", "guest", "other", "owner", "operator", "admin"})
    void contractUploadChecksTheParentBeforeWritingStorageOrRows(String actor) throws Exception {
        long before = fileCount(fixture.contractId());
        Multipart multipart = uploadBody(fixture.contractId());
        Reply reply = request("POST", "/files", token(actor), multipart.body(), multipart.contentType());
        if (List.of("owner", "operator").contains(actor)) {
            JsonNode uploaded = data(reply).path("files");
            assertThat(uploaded.size()).isEqualTo(1);
            assertPrivateProxyUrls(uploaded.get(0));
            assertThat(fileCount(fixture.contractId())).isEqualTo(before + 1);
            verify(storage, times(1)).store(any(MultipartFile.class), anyString(), anyString());
        } else {
            assertDenied(reply, actor);
            assertThat(fileCount(fixture.contractId())).isEqualTo(before);
            verifyNoInteractions(storage);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"anonymous", "guest", "other", "owner", "operator", "admin"})
    void contractParentDeletionRequiresOwnershipBeforeChangingRows(String actor) throws Exception {
        Reply reply = request("DELETE", "/files?fileParentType=contract&fileParentId=" + fixture.contractId(),
                token(actor), json.writeValueAsBytes(List.of(fixture.signatureId())), "application/json");
        if (List.of("owner", "operator").contains(actor)) {
            assertThat(reply.status()).isEqualTo(200);
            assertThat(deleted(fixture.signatureId())).isEqualTo("Y");
        } else {
            assertDenied(reply, actor);
            assertThat(deleted(fixture.signatureId())).isEqualTo("N");
        }
        assertThat(deleted(fixture.documentId())).isEqualTo("N");
        verifyNoInteractions(storage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"anonymous", "guest", "other", "owner", "operator", "admin"})
    void legacyAdministratorBulkDeletionIsClosedForAllActors(String actor) throws Exception {
        Reply reply = request("DELETE", "/files/admin", token(actor),
                json.writeValueAsBytes(List.of(fixture.documentId(), fixture.roomFileId())), "application/json");
        assertDenied(reply, actor);
        assertThat(deleted(fixture.documentId())).isEqualTo("N");
        assertThat(deleted(fixture.roomFileId())).isEqualTo("N");
        verifyNoInteractions(storage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"anonymous", "guest", "other", "owner", "operator", "admin"})
    void rawStaticContractPathsNeverBypassTheAuthorizedFileProxy(String actor) throws Exception {
        for (String prefix : List.of("CONTRACT", "contract", "CoNtRaCt", "%43ONTRACT")) {
            Reply reply = request("GET", "/static-files/" + prefix + "/" + fixture.contractId()
                    + "/synthetic/" + fixture.documentName() + "?download=1", token(actor), null, null);
            assertThat(reply.status()).isBetween(400, 499);
            assertNoPrivateStaticContent(reply);
        }
        for (String path : List.of("/static-files/contract?download=1",
                "/static-files/CONTRACT/" + fixture.contractId() + "/synthetic/" + fixture.documentName(),
                "/static-files/ROOM/../CONTRACT/" + fixture.contractId() + "/synthetic/" + fixture.documentName())) {
            for (String method : List.of("GET", "HEAD")) {
                Reply reply = request(method, path, token(actor), null, null);
                assertThat(reply.status()).isBetween(400, 499);
                assertNoPrivateStaticContent(reply);
            }
        }
        verifyNoInteractions(storage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"anonymous", "guest", "other", "owner", "operator", "admin"})
    void retainedAiDocumentsNeverExposeMetadataListsOrBytes(String actor) throws Exception {
        int id = seedAiDocument().getFileId();
        for (String path : List.of(
                "/files?fileParentType=%20ai_document%20&fileParentId=" + fixture.roomId(),
                "/files/" + id, "/files/" + id + "/view", "/files/" + id + "/download")) {
            Reply reply = request("GET", path, token(actor), null, null);
            assertError(reply, ErrorCode.FORBIDDEN);
            assertNoAiDocumentContent(reply);
        }
        verifyNoInteractions(storage);
    }

    @Test
    void legacyAiDocumentTypeCaseAndWhitespaceStillFailClosed() throws Exception {
        int id = seedAiDocument().getFileId();
        jdbc.update("UPDATE files SET file_parent_type=' aI_DoCuMeNt ' WHERE file_id=?", id);
        Reply reply = request("GET", "/files/" + id + "/download", token("operator"), null, null);
        assertError(reply, ErrorCode.FORBIDDEN);
        assertNoAiDocumentContent(reply);
        verifyNoInteractions(storage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"anonymous", "guest", "other", "owner", "operator", "admin"})
    void genericAiDocumentUploadAndDeletionAreRejectedWithoutSideEffects(String actor) throws Exception {
        int id = seedAiDocument().getFileId();
        long before = aiDocumentCount();
        Multipart upload = uploadBody("ai_document", fixture.roomId(), AI_DOCUMENT_NAME,
                XLSX_MEDIA_TYPE, AI_DOCUMENT_BYTES);
        assertDenied(request("POST", "/files", token(actor), upload.body(), upload.contentType()), actor);
        assertDenied(request("DELETE", "/files?fileParentType=ai_document&fileParentId=" + fixture.roomId(),
                token(actor), json.writeValueAsBytes(List.of(id)), "application/json"), actor);
        assertThat(aiDocumentCount()).isEqualTo(before);
        assertThat(deleted(id)).isEqualTo("N");
        verifyNoInteractions(storage);
    }

    @Test
    void mixedAiDocumentDeletionCannotChangeTheAuthorizedFileOrTheAiDocument() throws Exception {
        int id = seedAiDocument().getFileId();
        List<Integer> targets = List.of(fixture.signatureId(), id);
        byte[] body = json.writeValueAsBytes(targets);
        assertError(request("DELETE", "/files?fileParentType=CONTRACT&fileParentId=" + fixture.contractId(),
                token("owner"), body, "application/json"), ErrorCode.FORBIDDEN);
        assertError(request("DELETE", "/owner/files", token("operator"), body, "application/json"), ErrorCode.FORBIDDEN);
        withActor("owner", () -> assertBusiness(() -> service.softDeleteFiles(targets), ErrorCode.FORBIDDEN));
        assertThat(deleted(fixture.signatureId())).isEqualTo("N");
        assertThat(deleted(id)).isEqualTo("N");
        verifyNoInteractions(storage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"anonymous", "guest", "other", "owner", "operator", "admin"})
    void rawAiDocumentLocationsCannotServeRetainedFiles(String actor) throws Exception {
        String name = "synthetic-order-" + fixture.roomId() + ".xlsx";
        for (String dir : List.of("AI_DOCUMENT/" + fixture.roomId(), "aI_dOcUmEnT/" + fixture.roomId(),
                "ai/order_forms", "order_forms")) {
            Path file = STATIC_ROOT.resolve(dir).resolve(name);
            Files.createDirectories(file.getParent());
            Files.write(file, AI_DOCUMENT_BYTES);
            for (String method : List.of("GET", "HEAD")) {
                Reply reply = request(method, "/static-files/" + dir + "/" + name + "?download=1",
                        token(actor), null, null);
                assertThat(reply.status()).isBetween(400, 499);
                assertNoAiDocumentContent(reply);
            }
        }
        verifyNoInteractions(storage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"anonymous", "guest", "other", "owner", "operator", "admin"})
    void privateAiDocumentRoutesDenyEveryActorIncludingAnInternalTokenHolder(String actor) throws Exception {
        byte[] input = json.writeValueAsBytes(Map.of("userId", fixture.ownerId()));
        for (String route : List.of("/ai/payment/order-form", "/ai/payment/summary")) {
            assertDenied(request("POST", route, token(actor), input, "application/json"), actor);
        }
        assertDenied(request("GET", "/ai/payment/order-form/download/" + AI_DOCUMENT_NAME,
                token(actor), null, null), actor);
        Multipart upload = uploadBody("AI_DOCUMENT", fixture.roomId(), AI_DOCUMENT_NAME,
                XLSX_MEDIA_TYPE, AI_DOCUMENT_BYTES);
        long before = aiDocumentCount();
        assertDenied(request("POST", "/v1/ai/files/upload", token(actor), upload.body(), upload.contentType(),
                Map.of("X-Internal-Token", AI_INTERNAL_TOKEN)), actor);
        assertThat(aiDocumentCount()).isEqualTo(before);
        verifyNoInteractions(storage, aiGateway);
    }

    @ParameterizedTest
    @EnumSource(value = AiIntent.class, names = {"PAYMENT_ORDER_FORM_CREATE", "PAYMENT_SUMMARY_DOCUMENT"})
    void internalDocumentIntentsAreDeniedBeforeReachingTheAiGateway(AiIntent intent) {
        withActor("operator", () -> assertBusiness(() -> aiIntents.route(AiGatewayRequest.builder()
                .intent(intent).userId(fixture.operatorId()).slots(Map.of()).build()), ErrorCode.FORBIDDEN));
        verifyNoInteractions(storage, aiGateway);
    }

    @Test
    void ordinaryTextChatStillUsesTheAuthenticatedMemberAndAiGateway() throws Exception {
        assertError(request("POST", "/ai/payment/status", token("other"),
                json.writeValueAsBytes(Map.of()), "application/json"), ErrorCode.PAYMENT_NOT_FOUND);
        verifyNoInteractions(aiGateway);
        when(aiGateway.execute(any(AiGatewayRequest.class))).thenReturn(AiGatewayResponse.builder()
                .answer("Synthetic general chat answer").metadata(Map.of()).build());
        byte[] input = json.writeValueAsBytes(Map.of("prompt", "Synthetic general chat question",
                "userId", fixture.ownerId()));
        JsonNode result = data(request("POST", "/ai/chat", token("other"), input, "application/json"));
        assertThat(result.path("answer").asText()).isEqualTo("Synthetic general chat answer");
        verify(aiGateway).execute(org.mockito.ArgumentMatchers.argThat(call ->
                call.getIntent() == AiIntent.GENERAL_QA && fixture.otherId().equals(call.getUserId())));
        verifyNoInteractions(storage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"anonymous", "guest", "other", "owner", "operator", "admin"})
    void internalBulkMetadataHonorsEveryContractInTheRequestedMap(String actor) {
        withActor(actor, () -> {
            if ("operator".equals(actor)) {
                Map<Integer, List<FileResponse>> result = service.getActiveFilesMap(" contract ",
                        List.of(fixture.contractId(), fixture.otherContractId()));
                assertThat(result.keySet()).containsExactlyInAnyOrder(fixture.contractId(), fixture.otherContractId());
                result.values().forEach(list -> list.forEach(this::assertPrivateProxyUrls));
            } else if ("owner".equals(actor)) {
                assertThat(service.getActiveFilesMap("CONTRACT", List.of(fixture.contractId())).keySet())
                        .containsExactly(fixture.contractId());
                clearInvocations(storage);
                assertBusiness(() -> service.getActiveFilesMap("CONTRACT",
                        List.of(fixture.contractId(), fixture.otherContractId())), ErrorCode.FORBIDDEN);
                verifyNoInteractions(storage);
            } else {
                assertBusiness(() -> service.getActiveFilesMap("CONTRACT", List.of(fixture.contractId())),
                        deniedCode(actor));
                verifyNoInteractions(storage);
            }
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"anonymous", "guest", "owner"})
    void internalMixedFileDeletionCannotModifyAnotherMembersContract(String actor) {
        withActor(actor, () -> assertBusiness(
                () -> service.softDeleteFiles(List.of(fixture.roomFileId(), fixture.otherDocumentId())),
                deniedCode(actor)));
        assertThat(deleted(fixture.roomFileId())).isEqualTo("N");
        assertThat(deleted(fixture.otherDocumentId())).isEqualTo("N");
        verifyNoInteractions(storage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"anonymous", "guest", "owner"})
    void internalOrderChangesCannotModifyAnotherMembersContract(String actor) {
        withActor(actor, () -> assertBusiness(() -> service.updateFileOrder("CONTRACT",
                fixture.otherContractId(), List.of(fixture.otherDocumentId())), deniedCode(actor)));
        assertThat(jdbc.queryForObject("SELECT sort_order FROM files WHERE file_id=?",
                Integer.class, fixture.otherDocumentId())).isZero();
        verifyNoInteractions(storage);
    }

    @Test
    void anOwnerCanUpdateTheirOwnFileOrderAndUseTheExistingInternalCleanupMethod() {
        withActor("owner", () -> {
            service.updateFileOrder("contract", fixture.contractId(),
                    List.of(fixture.signatureId(), fixture.documentId()));
            assertThat(jdbc.queryForObject("SELECT sort_order FROM files WHERE file_id=?",
                    Integer.class, fixture.documentId())).isEqualTo(1);
            service.softDeleteFiles(List.of(fixture.signatureId()));
        });
        assertThat(deleted(fixture.signatureId())).isEqualTo("Y");
        verifyNoInteractions(storage);
    }

    @Test
    void publicRoomMetadataListAndImageBytesRemainPublic() throws Exception {
        JsonNode meta = data(request("GET", "/files/" + fixture.roomFileId(), null, null, null));
        assertThat(meta.path("viewUrl").asText()).isEqualTo("/files/" + fixture.roomFileId() + "/view");
        JsonNode listed = data(request("GET", "/files?fileParentType=ROOM&fileParentId=" + fixture.roomId(),
                null, null, null));
        assertThat(listed.size()).isEqualTo(1);
        Reply viewed = request("GET", "/files/" + fixture.roomFileId() + "/view", null, null, null);
        assertThat(viewed.status()).isEqualTo(200);
        assertThat(Arrays.equals(viewed.body(), PUBLIC_PNG)).isTrue();
        Reply downloaded = request("GET", "/files/" + fixture.roomFileId() + "/download", null, null, null);
        assertThat(downloaded.status()).isEqualTo(200);
        assertThat(Arrays.equals(downloaded.body(), PUBLIC_PNG)).isTrue();
        Path publicStatic = STATIC_ROOT.resolve("ROOM").resolve(String.valueOf(fixture.roomId()))
                .resolve("synthetic-public-room.png");
        Files.createDirectories(publicStatic.getParent());
        Files.write(publicStatic, PUBLIC_PNG);
        Reply staticImage = request("GET", "/static-files/ROOM/" + fixture.roomId()
                + "/synthetic-public-room.png", token("guest"), null, null);
        assertThat(staticImage.status()).isBetween(400, 499);
        assertThat(Arrays.equals(staticImage.body(), PUBLIC_PNG)).isFalse();
        verify(storage, never()).resolveViewUrl(anyInt(), anyString(), anyString());
        verify(storage, never()).resolveDownloadUrl(anyInt(), anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"owner", "operator", "admin"})
    void ordinaryRoutesStillHideDeletedContractFiles(String actor) throws Exception {
        Reply reply = request("GET", "/files/" + fixture.deletedId() + "/view", token(actor), null, null);
        assertError(reply, ErrorCode.FILE_NOT_FOUND);
        verifyNoInteractions(storage);
    }

    @Test
    void missingAndOrphanFilesFailClosedWithoutStorageReads() throws Exception {
        assertError(request("GET", "/files/" + Integer.MAX_VALUE + "/view", token("owner"), null, null),
                ErrorCode.FILE_NOT_FOUND);
        Reply orphan = request("GET", "/files/" + fixture.orphanId() + "/view", token("owner"), null, null);
        assertThat(orphan.status()).isBetween(400, 499);
        assertNoPrivateContent(orphan);
        verifyNoInteractions(storage);
    }

    @Test
    void aSignedGuestCannotBorrowARealOwnersSubjectToReadTheirContract() throws Exception {
        String forgedGuestSubject = jwt.createAccessToken(fixture.ownerId(), "guest");
        assertError(request("GET", "/files/" + fixture.documentId() + "/view", forgedGuestSubject, null, null),
                ErrorCode.FORBIDDEN);
        verifyNoInteractions(storage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"refresh", "expired"})
    void invalidAccessCredentialsAreRejectedBeforeAnyStorageAccess(String kind) throws Exception {
        String token = "refresh".equals(kind) ? jwt.createRefreshToken(fixture.ownerId())
                : jwt.createAccessToken(fixture.ownerId(), "tenant", -60_000);
        assertError(request("GET", "/files/" + fixture.documentId() + "/view", token, null, null),
                "refresh".equals(kind) ? ErrorCode.TOKEN_TYPE_INVALID : ErrorCode.TOKEN_EXPIRED);
        verifyNoInteractions(storage);
    }

    @Test
    void ownerRevocationRevokesTheOldTokensContractFileAccessOnTheNextRequest() throws Exception {
        String oldAdmin = token("operator");
        jdbc.update("UPDATE account_role_grants SET grant_state='REVOKED' WHERE user_id=? AND role_code='OWNER'", fixture.operatorId());
        assertError(request("GET", "/files/" + fixture.documentId() + "/view", oldAdmin, null, null), ErrorCode.FORBIDDEN);
        assertError(request("GET", "/files/admin/" + fixture.deletedId() + "/view", oldAdmin, null, null), ErrorCode.FORBIDDEN);
        verifyNoInteractions(storage);
    }

    @Test
    void internalAdministratorMetadataMethodsCannotBeCalledByTheOwner() {
        withActor("owner", () -> {
            assertBusiness(() -> service.getFileForAdmin(fixture.deletedId()), ErrorCode.FORBIDDEN);
            assertBusiness(() -> service.getAllFilesForAdmin("CONTRACT", fixture.contractId()), ErrorCode.FORBIDDEN);
        });
        verifyNoInteractions(storage);
    }

    @Test
    void storedContractTypeCaseAndWhitespaceCannotTurnItsFilePublic() throws Exception {
        jdbc.update("UPDATE files SET file_parent_type=' contract ' WHERE file_id=?", fixture.documentId());
        assertError(request("GET", "/files/" + fixture.documentId() + "/view", null, null, null), ErrorCode.UNAUTHORIZED);
        JsonNode owner = data(request("GET", "/files/" + fixture.documentId(), token("owner"), null, null));
        assertPrivateProxyUrls(owner);
        verify(storage, never()).read(anyString(), anyString());
        verify(storage, never()).resolveViewUrl(anyInt(), anyString(), anyString());
        verify(storage, never()).resolveDownloadUrl(anyInt(), anyString(), anyString());
    }

    @Test
    void storageAwareContractResponseOverloadsNeverReturnDirectStorageUrls() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            Contract contract = contracts.findById(fixture.contractId()).orElseThrow();
            String expected = "/files/" + fixture.documentId() + "/download";
            assertThat(ContractResponse.fromEntity(contract, storage).getContractPdfUrl()).isEqualTo(expected);
            assertThat(AdminContractSummaryResponse.fromEntity(contract, "synthetic-contract.png", storage)
                    .getContractPdfUrl()).isEqualTo(expected);
        });
        verifyNoInteractions(storage);
    }

    @Test
    void aCallerWriteTransactionCanUploadAndReadItsUncommittedOwnedContractSignature() throws Exception {
        withActor("owner", () -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            User owner = users.findById(fixture.ownerId()).orElseThrow();
            Room room = rooms.findById(fixture.roomId()).orElseThrow();
            Contract uncommitted = contract(contracts, owner, room);
            var upload = service.uploadFiles(FileUploadRequest.builder().fileParentType("CONTRACT")
                    .fileParentId(uncommitted.getContractId()).files(List.of(
                            new MockMultipartFile("files", "synthetic-signature.png", "image/png", PRIVATE_PNG))).build());
            assertThat(upload.getFiles()).hasSize(1);
            FileResponse signature = service.getFile(upload.getFiles().get(0).getFileId());
            assertThat(signature.getFileParentId()).isEqualTo(uncommitted.getContractId());
            assertPrivateProxyUrls(signature);
            status.setRollbackOnly();
        }));
        verify(storage, times(1)).store(any(MultipartFile.class), anyString(), anyString());
        verify(storage, never()).read(anyString(), anyString());
    }

    /** Optional browser mode uses only synthetic accounts and leaves normal CI without an extra test or skip. */
    @AfterAll
    static void optionalBrowserFixture(
            @Autowired UserRepository users, @Autowired BuildingRepository buildings,
            @Autowired RoomRepository rooms, @Autowired ContractRepository contracts,
            @Autowired UploadFileRepository files, @Autowired PlatformTransactionManager transactions,
            @Autowired PasswordEncoder passwords, @Autowired StorageService storage,
            @Autowired ObjectMapper json, @LocalServerPort int port) throws Exception {
        try {
            String target = System.getenv("UNIPLACE_CONTRACT_BROWSER_READY");
            if (target == null || target.isBlank()) return;
            configureStorage(storage, false);
            Map<String, Object> readyValue = new TransactionTemplate(transactions).execute(status -> {
                String passwordHash = passwords.encode(BROWSER_PASSWORD);
                User owner = user(users, "contract-browser-owner", UserRole.tenant, passwordHash);
                user(users, "contract-browser-other", UserRole.user, passwordHash);
                user(users, "contract-browser-admin", UserRole.admin, passwordHash);
                Building building = buildings.saveAndFlush(Building.builder().buildingNm("Private file browser")
                        .buildingAddr("Synthetic browser fixture address").build());
                Room room = room(rooms, building, 901);
                Contract contract = contract(contracts, owner, room);
                UploadFile document = file(files, "CONTRACT", contract.getContractId(), "synthetic-contract.png");
                UploadFile publicImage = file(files, "ROOM", room.getRoomId(), "synthetic-public-room.png");
                contract.setContractPdfFileId(document.getFileId());
                contracts.flush();
                return Map.<String, Object>of("baseUrl", "http://127.0.0.1:" + port + "/api",
                        "ownerId", owner.getUserId(), "contractId", contract.getContractId(),
                        "contractFileId", document.getFileId(), "roomFileId", publicImage.getFileId(),
                        "roomId", room.getRoomId());
            });
            Path ready = Path.of(target).toAbsolutePath();
            Path done = Path.of(ready + ".done");
            if (Files.exists(done, LinkOption.NOFOLLOW_LINKS)) throw new AssertionError("Browser marker already exists");
            Files.createDirectories(ready.getParent());
            Files.writeString(ready, json.writeValueAsString(readyValue),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
            while (!Files.exists(done)) {
                if (System.nanoTime() >= deadline) throw new AssertionError("Browser verification timed out");
                Thread.sleep(200);
            }
            assertThat(Files.readString(done)).isEqualTo("passed");
        } finally {
            try (Stream<Path> paths = Files.walk(STATIC_ROOT)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private String readPath(String endpoint, boolean admin) {
        String root = admin ? "/files/admin" : "/files";
        int id = admin ? fixture.deletedId() : fixture.documentId();
        return switch (endpoint) {
            case "list" -> root + "?fileParentType=contract&fileParentId=" + fixture.contractId();
            case "metadata" -> root + "/" + id;
            default -> root + "/" + id + "/" + endpoint;
        };
    }

    private String token(String actor) {
        return switch (actor) {
            case "anonymous" -> null;
            case "guest" -> jwt.createAccessToken("GUEST_synthetic_contract_file", "guest");
            case "owner" -> jwt.createAccessToken(fixture.ownerId(), "tenant");
            case "other" -> jwt.createAccessToken(fixture.otherId(), "user");
            case "admin" -> jwt.createAccessToken(fixture.adminId(), "admin");
            case "operator" -> jwt.createAccessToken(fixture.operatorId(), "owner");
            default -> throw new IllegalArgumentException("Unknown synthetic actor");
        };
    }

    private void withActor(String actor, Runnable action) {
        SecurityContextHolder.clearContext();
        try {
            String token = token(actor);
            if (token != null) SecurityContextHolder.getContext().setAuthentication(jwt.getAuthentication(token));
            action.run();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private Reply request(String method, String path, String token, byte[] body, String contentType) throws Exception {
        return request(method, path, token, body, contentType, Map.of());
    }

    private Reply request(String method, String path, String token, byte[] body, String contentType,
            Map<String, String> extraHeaders) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api" + path))
                .timeout(Duration.ofSeconds(15)).header("Accept", "*/*");
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (contentType != null) request.header("Content-Type", contentType);
        extraHeaders.forEach(request::header);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        HttpResponse<byte[]> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        return new Reply(response.statusCode(), response.body(), response.headers().map());
    }

    private JsonNode data(Reply response) throws Exception {
        assertThat(response.status()).isEqualTo(200);
        JsonNode parsed = json.readTree(response.body());
        assertThat(parsed.path("success").asBoolean()).isTrue();
        return parsed.path("data");
    }

    private void assertPrivateProxyUrls(JsonNode file) {
        int id = file.path("fileId").asInt();
        assertThat(id).isPositive();
        assertThat(file.path("viewUrl").asText()).isEqualTo("/files/" + id + "/view");
        assertThat(file.path("downloadUrl").asText()).isEqualTo("/files/" + id + "/download");
        assertThat(file.path("adminViewUrl").asText()).isEqualTo("/files/" + id + "/view");
        assertThat(file.path("adminDownloadUrl").asText()).isEqualTo("/files/" + id + "/download");
    }

    private void assertPrivateProxyUrls(FileResponse file) {
        int id = file.getFileId();
        assertThat(file.getViewUrl()).isEqualTo("/files/" + id + "/view");
        assertThat(file.getDownloadUrl()).isEqualTo("/files/" + id + "/download");
        assertThat(file.getAdminViewUrl()).isEqualTo("/files/" + id + "/view");
        assertThat(file.getAdminDownloadUrl()).isEqualTo("/files/" + id + "/download");
    }

    private void assertDenied(Reply reply, String actor) throws Exception {
        assertError(reply, deniedCode(actor));
        assertNoPrivateContent(reply);
    }

    private static ErrorCode deniedCode(String actor) {
        return "anonymous".equals(actor) ? ErrorCode.UNAUTHORIZED : ErrorCode.FORBIDDEN;
    }

    private void assertError(Reply response, ErrorCode expected) throws Exception {
        assertThat(response.status()).isEqualTo(expected.getStatus().value());
        JsonNode parsed = json.readTree(response.body());
        assertThat(parsed.path("success").asBoolean()).isFalse();
        assertThat(parsed.path("errorCode").asText()).isEqualTo(expected.getCode());
    }

    private static void assertNoPrivateStaticContent(Reply reply) {
        assertThat(Arrays.equals(reply.body(), PRIVATE_PNG)).isFalse();
        assertThat(reply.header("content-type")).doesNotStartWith("image/");
        // A firewall rejection may echo the caller-supplied URI; that is not stored metadata.
        String text = new String(reply.body(), StandardCharsets.UTF_8);
        assertThat(text).doesNotContain("\"filePath\"", "\"renameFilename\"", "\"viewUrl\"", "synthetic-storage.example");
    }

    private static void assertNoAiDocumentContent(Reply reply) {
        assertThat(Arrays.equals(reply.body(), AI_DOCUMENT_BYTES)).isFalse();
        assertThat(reply.header("content-type")).doesNotContain("spreadsheetml");
        assertThat(reply.header("content-disposition")).isEmpty();
        assertThat(new String(reply.body(), StandardCharsets.UTF_8))
                .doesNotContain("\"filePath\"", "\"renameFilename\"", "\"viewUrl\"", "synthetic-storage.example");
    }

    private static void assertNoPrivateContent(Reply reply) {
        assertThat(Arrays.equals(reply.body(), PRIVATE_PNG)).isFalse();
        String text = new String(reply.body(), StandardCharsets.UTF_8);
        assertThat(text.contains("CONTRACT/")).isFalse();
        assertThat(text.contains("contract-document.png")).isFalse();
        assertThat(text.contains("synthetic-storage.example")).isFalse();
    }

    private static void assertNoStore(Reply response) {
        assertThat(response.header("cache-control").toLowerCase(Locale.ROOT)).contains("no-store");
    }

    private static void assertBusiness(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(expected));
    }

    private String deleted(int id) {
        return jdbc.queryForObject("SELECT delete_yn FROM files WHERE file_id=?", String.class, id);
    }

    private long fileCount(int contractId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM files WHERE file_parent_type='CONTRACT' AND file_parent_id=?",
                Long.class, contractId);
    }

    private long aiDocumentCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM files WHERE file_parent_type='AI_DOCUMENT' AND file_parent_id=?",
                Long.class, fixture.roomId());
    }

    private UploadFile seedAiDocument() {
        int parent = fixture.roomId();
        return files.saveAndFlush(UploadFile.builder().fileParentType("AI_DOCUMENT").fileParentId(parent)
                .filePath("AI_DOCUMENT/" + parent + "/synthetic").originFilename(AI_DOCUMENT_NAME)
                .renameFilename(AI_DOCUMENT_NAME).fileSize(AI_DOCUMENT_BYTES.length).fileType(".xlsx").sortOrder(0).build());
    }

    private static User user(UserRepository users, String id, UserRole role, String passwordHash) {
        return users.saveAndFlush(User.builder().userId(id).userNm("Synthetic contract member")
                .userEmail(id + "@example.test").userPwd(passwordHash).userBirth(LocalDate.of(2000, 1, 1))
                .userTel("010-0000-0000").userNickname(id).userRole(role).firstSign("N").build());
    }

    private static Room room(RoomRepository rooms, Building building, int number) {
        return rooms.saveAndFlush(Room.builder().building(building).roomNo(number).floor(1)
                .roomSize(new java.math.BigDecimal("20.00")).rentPrice(new java.math.BigDecimal("500000"))
                .deposit(new java.math.BigDecimal("1000000")).manageFee(new java.math.BigDecimal("50000"))
                .roomSt(RoomStatus.contracted).build());
    }

    private static Contract contract(ContractRepository contracts, User user, Room room) {
        return contracts.saveAndFlush(Contract.builder().user(user).room(room)
                .contractStart(LocalDate.now().minusDays(10)).contractEnd(LocalDate.now().plusDays(40))
                .rentPrice(new java.math.BigDecimal("500000")).deposit(new java.math.BigDecimal("1000000"))
                .manageFee(new java.math.BigDecimal("50000")).paymentDay(1).contractSt(ContractStatus.active)
                .lessorNm("Synthetic lessor").lessorTel("010-0000-0000").lessorAddr("Synthetic fixture address")
                .lessorRrn("000000-0000000").build());
    }

    private static UploadFile file(UploadFileRepository files, String type, int parent, String name) {
        return files.saveAndFlush(UploadFile.builder().fileParentType(type).fileParentId(parent)
                .filePath(type + "/" + parent + "/synthetic").originFilename(name).renameFilename(name)
                .fileSize(("CONTRACT".equals(type) ? PRIVATE_PNG : PUBLIC_PNG).length).fileType(".png")
                .sortOrder(0).build());
    }

    private static Multipart uploadBody(int contractId) throws IOException {
        return uploadBody("CONTRACT", contractId, "synthetic-signature.png", "image/png", PRIVATE_PNG);
    }

    private static Multipart uploadBody(String type, int parentId, String name, String mediaType, byte[] content)
            throws IOException {
        String boundary = "SyntheticContractBoundary" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (var entry : Map.of("fileParentType", type, "fileParentId", String.valueOf(parentId)).entrySet()) {
            bytes.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + entry.getKey()
                    + "\"\r\n\r\n" + entry.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        bytes.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\"" + name + "\"\r\n"
                + "Content-Type: " + mediaType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        bytes.write(content);
        bytes.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return new Multipart(bytes.toByteArray(), "multipart/form-data; boundary=" + boundary);
    }

    private static void configureStorage(StorageService storage, boolean externalUrls) throws IOException {
        when(storage.resolveViewUrl(anyInt(), anyString(), anyString())).thenAnswer(invocation ->
                externalUrls ? "https://synthetic-storage.example/" + invocation.getArgument(1) + "/" + invocation.getArgument(2)
                        : "/files/" + invocation.getArgument(0) + "/view");
        when(storage.resolveDownloadUrl(anyInt(), anyString(), anyString())).thenAnswer(invocation ->
                externalUrls ? "https://synthetic-storage.example/" + invocation.getArgument(1) + "/" + invocation.getArgument(2)
                        : "/files/" + invocation.getArgument(0) + "/download");
        when(storage.read(anyString(), anyString())).thenAnswer(invocation -> {
            String parent = invocation.getArgument(0);
            if (parent.startsWith("CONTRACT/")) return new ByteArrayInputStream(PRIVATE_PNG);
            if (parent.startsWith("ROOM/")) return new ByteArrayInputStream(PUBLIC_PNG);
            if (parent.startsWith("AI_DOCUMENT/")) return new ByteArrayInputStream(AI_DOCUMENT_BYTES);
            throw new FileNotFoundException("Unknown synthetic storage key");
        });
    }

    private static void writeStaticPrivateFiles(int contractId, String name) throws IOException {
        for (String type : List.of("CONTRACT", "contract", "CoNtRaCt")) {
            Path parent = STATIC_ROOT.resolve(type).resolve(String.valueOf(contractId)).resolve("synthetic");
            Files.createDirectories(parent);
            Files.write(parent.resolve(name), PRIVATE_PNG);
        }
    }

    private static Path temporaryRoot() {
        try {
            return Files.createTempDirectory("uniplace-contract-files-");
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static byte[] png(String label, Color background) {
        try {
            BufferedImage image = new BufferedImage(760, 280, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            try {
                graphics.setColor(background);
                graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
                graphics.setColor(Color.BLACK);
                graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 26));
                graphics.drawString(label, 35, 90);
                graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 18));
                graphics.drawString("Fixture document. No customer data.", 35, 145);
                graphics.drawString("Generated only for local authorization tests.", 35, 185);
            } finally {
                graphics.dispose();
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", bytes)) throw new IOException("PNG writer unavailable");
            return bytes.toByteArray();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private record Fixture(String ownerId, String otherId, String adminId, int contractId, int otherContractId,
            int roomId, int documentId, int signatureId, int deletedId, int otherDocumentId, int roomFileId,
            int orphanId, String documentPath, String documentName, String operatorId) {}
    private record Multipart(byte[] body, String contentType) {}
    private record Reply(int status, byte[] body, Map<String, List<String>> headers) {
        String header(String name) {
            return headers.getOrDefault(name.toLowerCase(Locale.ROOT), List.of("")).get(0);
        }
    }
}
