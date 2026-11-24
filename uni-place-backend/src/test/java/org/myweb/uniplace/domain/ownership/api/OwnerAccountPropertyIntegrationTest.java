package org.myweb.uniplace.domain.ownership.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.uniplace.domain.contract.application.ContractImageService;
import org.myweb.uniplace.domain.contract.domain.entity.Contract;
import org.myweb.uniplace.domain.contract.domain.entity.Resident;
import org.myweb.uniplace.domain.contract.domain.enums.ContractStatus;
import org.myweb.uniplace.domain.contract.repository.ContractRepository;
import org.myweb.uniplace.domain.contract.repository.ResidentRepository;
import org.myweb.uniplace.domain.file.domain.entity.UploadFile;
import org.myweb.uniplace.domain.file.repository.UploadFileRepository;
import org.myweb.uniplace.domain.notification.application.NotificationService;
import org.myweb.uniplace.domain.ownership.application.publicapi.OwnershipAccess;
import org.myweb.uniplace.domain.property.domain.entity.Building;
import org.myweb.uniplace.domain.property.domain.entity.Room;
import org.myweb.uniplace.domain.property.domain.enums.RoomStatus;
import org.myweb.uniplace.domain.property.repository.BuildingRepository;
import org.myweb.uniplace.domain.property.repository.RoomRepository;
import org.myweb.uniplace.domain.user.domain.entity.User;
import org.myweb.uniplace.domain.user.domain.enums.AccountKind;
import org.myweb.uniplace.domain.user.domain.enums.UserRole;
import org.myweb.uniplace.domain.user.repository.UserRepository;
import org.myweb.uniplace.global.security.AuthUser;
import org.myweb.uniplace.global.security.JwtProvider;
import org.myweb.uniplace.global.slack.SlackNotificationService;
import org.myweb.uniplace.global.storage.StorageService;
import org.myweb.uniplace.support.TestOwnershipFixtures;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real account, property, contract and file boundaries; no real storage or external delivery. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1", "server.servlet.context-path=/api",
        "jwt.secret=ownership-test-secret-with-at-least-32-bytes",
        "jwt.access-exp=3600000", "jwt.refresh-exp=86400000",
        "spring.mail.host=localhost", "spring.mail.port=2525",
        "spring.mail.username=local", "spring.mail.password=local",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.show-sql=false",
        "logging.level.org.hibernate.SQL=WARN", "payment.reconcile.enabled=false",
        "ai.fastapi.base-url=http://127.0.0.1:9", "slack.webhook-url=",
        "contract.status-transition-cron=-", "storage.type=local"
})
@Testcontainers
class OwnerAccountPropertyIntegrationTest {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String BROWSER_PASSWORD = "Synthetic-owner-browser-only-123!";
    private static final String PASSWORD_HASH = new BCryptPasswordEncoder().encode(BROWSER_PASSWORD);
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a4XkAAAAASUVORK5CYII=");
    private static final Path UPLOAD_ROOT = temporaryRoot();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("owner_account_property").withUsername("ownership_test")
            .withPassword("ownership-test-only");

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
    @Autowired UserRepository users;
    @Autowired BuildingRepository buildings;
    @Autowired RoomRepository rooms;
    @Autowired ContractRepository contracts;
    @Autowired ResidentRepository residents;
    @Autowired UploadFileRepository files;
    @Autowired OwnershipAccess ownership;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean StorageService storage;
    @MockitoBean ContractImageService images;
    @MockitoBean NotificationService notifications;
    @MockitoBean SlackNotificationService slack;

    private Fixture f;

    @BeforeEach
    void committedSyntheticOwnersAndProperties() throws Exception {
        configureEffects(storage, images, files);
        f = new TransactionTemplate(transactions).execute(status -> {
            Building a = building(buildings, "Owned A");
            Building b = building(buildings, "Owned B");
            String suffix = String.valueOf(a.getBuildingId());
            User owner = user(users, "owner-a-" + suffix, UserRole.user);
            User other = user(users, "owner-b-" + suffix, UserRole.user);
            User customer = user(users, "owner-customer-" + suffix, UserRole.user);
            User applicant = user(users, "owner-applicant-" + suffix, UserRole.user);
            User staff = user(users, "owner-staff-" + suffix, UserRole.admin);
            Room ar = room(rooms, a, 901);
            Room br = room(rooms, b, 901);
            TestOwnershipFixtures.buildingOwner(jdbc, owner.getUserId(), a.getBuildingId());
            TestOwnershipFixtures.buildingOwner(jdbc, other.getUserId(), b.getBuildingId());
            return new Fixture(owner.getUserId(), other.getUserId(), customer.getUserId(), applicant.getUserId(),
                    staff.getUserId(), a.getBuildingId(), b.getBuildingId(), ar.getRoomId(), br.getRoomId(),
                    a.getBuildingNm(), b.getBuildingNm());
        });
        clearInvocations(storage, images, notifications, slack);
    }

    @Test
    void oneMemberIdentityGainsOwnerOnTheNextRequestAfterStaffApprovalUsingTheSameToken() throws Exception {
        String oldToken = token(f.applicant());
        JsonNode initial = me(oldToken);
        assertThat(initial.path("accountKind").asText()).isEqualTo("MEMBER");
        assertThat(roleNames(initial)).contains("CUSTOMER").doesNotContain("OWNER", "ADMIN");
        assertThat(data(call("GET", "/users/me/owner-application", oldToken, null)).path("status").asText())
                .isEqualTo("NONE");
        assertThat(data(call("GET", "/users/me/asset-claims", oldToken, null)).isEmpty()).isTrue();
        State before = state();
        assertStatus(upload("POST", "/owner/buildings", buildingFields("Unapproved draft"), null, null, oldToken), 403);
        assertThat(state()).isEqualTo(before);
        verifyNoInteractions(storage, images, notifications, slack);

        JsonNode pending = data(call("POST", "/users/me/owner-application", oldToken,
                Map.of("applicationNote", "Synthetic applicant verification reference")));
        assertThat(pending.path("status").asText()).isEqualTo("PENDING");
        assertThat(roleNames(me(oldToken))).doesNotContain("OWNER");
        JsonNode applications = data(call("GET", "/admin/owner-applications?status=PENDING", staffToken(), null));
        assertThat(findBy(applications, "userId", f.applicant())).hasSize(1);
        JsonNode approved = data(call("POST", "/admin/owner-applications/" + f.applicant() + "/approve",
                staffToken(), Map.of("reviewNote", "Synthetic identity and owner eligibility checked")));
        assertThat(approved.path("status").asText()).isEqualTo("APPROVED");
        assertThat(roleNames(me(oldToken))).contains("CUSTOMER", "OWNER").doesNotContain("ADMIN");
        assertStatus(call("GET", "/owner/buildings", oldToken, null), 200);
    }

    @Test
    void staleOwnerReviewCannotApproveNewEvidenceOrRevokeACompletedReview() throws Exception {
        String applicant = token(f.applicant());
        String applicationPath = "/users/me/owner-application";
        String reviewPath = "/admin/owner-applications/" + f.applicant();
        data(call("POST", applicationPath, applicant, Map.of("applicationNote", "Original synthetic application")));
        JsonNode stale = only(findBy(data(call("GET", "/admin/owner-applications?status=PENDING", staffToken(), null)),
                "userId", f.applicant()));
        JsonNode current = data(call("POST", applicationPath, applicant, Map.of("applicationNote", "Updated synthetic application")));
        assertThat(current.path("updatedAt").asText()).isNotBlank().isNotEqualTo(stale.path("updatedAt").asText());
        State pending = state();
        assertReviewConflict(call("POST", reviewPath + "/approve", staffToken(),
                Map.of("reviewNote", "Reviewed original application", "expectedUpdatedAt", stale.path("updatedAt"))));
        assertThat(state()).isEqualTo(pending);
        assertThat(roleNames(me(applicant))).doesNotContain("OWNER");
        JsonNode approved = data(call("POST", reviewPath + "/approve", staffToken(),
                Map.of("reviewNote", "Reviewed updated application", "expectedUpdatedAt", current.path("updatedAt"))));
        assertThat(approved.path("status").asText()).isEqualTo("APPROVED");
        State afterApproval = state();
        assertReviewConflict(call("POST", reviewPath + "/reject", staffToken(),
                Map.of("reviewNote", "Stale opposite decision", "expectedUpdatedAt", current.path("updatedAt"))));
        assertThat(state()).isEqualTo(afterApproval);
        assertThat(roleNames(me(applicant))).contains("OWNER");
    }

    @Test
    void staffCannotBecomeAnOwnerOrInheritCustomerBusinessPrivileges() throws Exception {
        JsonNode staff = me(staffToken());
        assertThat(staff.path("accountKind").asText()).isEqualTo("STAFF");
        assertThat(roleNames(staff)).containsExactly("ADMIN");
        assertStatus(call("GET", "/admin/users", staffToken(), null), 200);
        State before = state();
        assertStatus(call("POST", "/users/me/owner-application", staffToken(),
                Map.of("applicationNote", "A staff account must stay separate")), 403);
        assertStatus(call("GET", "/contracts/me", staffToken(), null), 403);
        assertStatus(call("GET", "/owner/buildings", staffToken(), null), 403);
        assertThat(state()).isEqualTo(before);
        verifyNoInteractions(storage, images, notifications, slack);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/admin/buildings", "/admin/rooms", "/admin/spaces", "/admin/contracts", "/admin/residents",
            "/admin/property-dashboard", "/admin/tour-reservations", "/admin/space-reservations",
            "/owner/buildings", "/owner/rooms", "/owner/contracts", "/owner/residents"})
    void staffAccountManagementDoesNotRetainLegacyBusinessRoutes(String path) throws Exception {
        State before = state();
        assertStatus(call("GET", path, staffToken(), null), 403);
        assertThat(state()).isEqualTo(before);
    }

    @Test
    void anOwnerCannotReviewTheirOwnApplicationOrGrantAccountPrivileges() throws Exception {
        assertStatus(call("POST", "/admin/owner-applications/" + f.applicant() + "/approve", token(f.owner()),
                Map.of("reviewNote", "Untrusted owner cannot approve accounts")), 403);
        assertThat(roleNames(me(token(f.applicant())))).doesNotContain("OWNER");
    }

    @Test
    void aSignedGuestCannotBorrowTheRealOwnersSubject() throws Exception {
        String guest = jwt.createAccessToken(f.owner(), "guest");
        assertStatus(call("GET", "/owner/buildings/" + f.building(), guest, null), 403);
        assertStatus(call("POST", "/users/me/owner-application", guest,
                Map.of("applicationNote", "Signed guest is not a member")), 403);
        verifyNoInteractions(storage, images, notifications, slack);
    }

    @Test
    void aSignedLegacyAdminClaimCannotTurnAMemberIntoStaffOrOwner() throws Exception {
        String elevatedClaim = jwt.createAccessToken(f.applicant(), "admin");
        assertThat(roleNames(me(elevatedClaim))).contains("CUSTOMER").doesNotContain("ADMIN", "OWNER");
        assertStatus(call("GET", "/admin/users", elevatedClaim, null), 403);
        assertStatus(call("GET", "/owner/buildings", elevatedClaim, null), 403);
        verifyNoInteractions(storage, images, notifications, slack);
    }

    @Test
    void syntheticOwnerFixturesNeverSilentlyConvertAStaffIdentity() {
        State before = state();
        assertThatThrownBy(() -> TestOwnershipFixtures.approveOwner(jdbc, f.staff()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TestOwnershipFixtures.rentalOperator(jdbc, f.staff(), f.building()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(state()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT account_kind FROM users WHERE user_id=?", String.class, f.staff()))
                .isEqualTo("STAFF");
    }

    @Test
    void revokingOwnerOnTheNextRequestPreservesCustomerAndUnrelatedTenantPrivileges() throws Exception {
        int contract = activeTenancy(f.owner(), f.otherRoom());
        String old = token(f.owner());
        assertThat(roleNames(me(old))).contains("CUSTOMER", "OWNER", "TENANT");
        data(call("POST", "/admin/owner-applications/" + f.owner() + "/reject", staffToken(),
                Map.of("reviewNote", "Synthetic owner eligibility revoked")));
        assertThat(roleNames(me(old))).contains("CUSTOMER", "TENANT").doesNotContain("OWNER");
        assertStatus(call("GET", "/owner/buildings/" + f.building(), old, null), 403);
        assertThat(findBy(data(call("GET", "/contracts/me", old, null)), "contractId", contract)).hasSize(1);
    }

    @Test
    void aRequestedContractsManualResidentAndLegacyTenantLabelDoNotGrantTenantEntitlement() throws Exception {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            Contract requested = contract(users.findById(f.applicant()).orElseThrow(),
                    rooms.findById(f.room()).orElseThrow(), ContractStatus.requested);
            residents.saveAndFlush(Resident.builder().buildingId(f.building()).contractId(requested.getContractId())
                    .userId(f.applicant()).build());
            jdbc.update("UPDATE users SET user_role='tenant' WHERE user_id=?", f.applicant());
        });
        assertThat(roleNames(me(token(f.applicant())))).contains("CUSTOMER").doesNotContain("TENANT", "OWNER");
    }

    @ParameterizedTest
    @ValueSource(strings = {"no-resident", "future-start", "expired-end", "different-resident", "different-building"})
    void tenantRequiresADateValidActiveContractAndItsMatchingResident(String counterexample) throws Exception {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            Contract active = contract(users.findById(f.applicant()).orElseThrow(),
                    rooms.findById(f.room()).orElseThrow(), ContractStatus.active);
            if (!counterexample.equals("no-resident")) {
                residents.saveAndFlush(Resident.builder()
                        .buildingId(counterexample.equals("different-building") ? f.otherBuilding() : f.building())
                        .contractId(active.getContractId())
                        .userId(counterexample.equals("different-resident") ? f.customer() : f.applicant()).build());
            }
            if (counterexample.equals("future-start")) {
                jdbc.update("UPDATE contract SET contract_start=? WHERE contract_id=?", LocalDate.now(KST).plusDays(1),
                        active.getContractId());
            } else if (counterexample.equals("expired-end")) {
                jdbc.update("UPDATE contract SET contract_end=? WHERE contract_id=?", LocalDate.now(KST).minusDays(1),
                        active.getContractId());
            }
        });
        String legacyTenant = jwt.createAccessToken(f.applicant(), "tenant");
        assertThat(roleNames(me(legacyTenant))).contains("CUSTOMER").doesNotContain("TENANT", "OWNER");
        verifyNoInteractions(storage, images, notifications, slack);
    }

    @ParameterizedTest
    @ValueSource(strings = {"starts-today", "ends-today"})
    void tenantEntitlementIncludesBothValidDateBoundaries(String boundary) throws Exception {
        int id = activeTenancy(f.owner(), f.otherRoom());
        String column = boundary.equals("starts-today") ? "contract_start" : "contract_end";
        jdbc.update("UPDATE contract SET " + column + "=? WHERE contract_id=?", LocalDate.now(KST), id);
        assertThat(roleNames(me(token(f.owner())))).contains("CUSTOMER", "OWNER", "TENANT");
    }

    @Test
    void endingATenancyRemovesOnlyTenantWhileOwnerAndCustomerRemain() throws Exception {
        int id = activeTenancy(f.owner(), f.otherRoom());
        TestOwnershipFixtures.contractParty(jdbc, f.otherOwner(), id);
        String old = token(f.owner());
        assertThat(roleNames(me(old))).contains("CUSTOMER", "OWNER", "TENANT");
        data(upload("PUT", "/owner/contracts/" + id, Map.of("contractStatus", "ended"), null, null,
                token(f.otherOwner())));
        assertThat(roleNames(me(old))).contains("CUSTOMER", "OWNER").doesNotContain("TENANT");
        assertStatus(call("GET", "/owner/buildings/" + f.building(), old, null), 200);
    }

    @Test
    void newDraftIsOwnedByThePrincipalAndPublishedOnlyAfterSeparateAssetVerification() throws Exception {
        long beforePublic = data(call("GET", "/buildings?page=1&size=1", null, null)).path("totalElements").asLong();
        Map<String, String> fields = buildingFields("Verified draft");
        fields.put("ownerId", f.otherOwner());
        fields.put("userId", f.staff());
        JsonNode created = data(upload("POST", "/owner/buildings", fields, null, null, token(f.owner())));
        int buildingId = positiveId(created, "buildingId");
        assertThat(jdbc.queryForObject("SELECT user_id FROM building_memberships WHERE building_id=?",
                String.class, buildingId)).isEqualTo(f.owner());
        assertThat(jdbc.queryForObject("SELECT state FROM building_memberships WHERE building_id=?",
                String.class, buildingId)).isEqualTo("PENDING");
        assertStatus(call("GET", "/buildings/" + buildingId, null, null), 404);
        assertStatus(call("GET", "/owner/buildings/" + buildingId, token(f.owner()), null), 200);
        assertStatus(call("GET", "/owner/buildings/" + buildingId, token(f.otherOwner()), null), 403);
        String revised = "Synthetic edited private draft " + buildingId;
        data(upload("PUT", "/owner/buildings/" + buildingId, Map.of("buildingNm", revised), null, null, token(f.owner())));
        int draftRoom = positiveId(data(upload("POST", "/owner/rooms", roomFields(buildingId, revised, 902),
                null, null, token(f.owner()))), "roomId");
        assertStatus(call("GET", "/rooms/" + draftRoom, null, null), 404);
        JsonNode stillPublic = data(call("GET", "/buildings?page=1&size=1", null, null));
        assertThat(stillPublic.path("totalElements").asLong()).isEqualTo(beforePublic);
        assertThat(stillPublic.path("content").get(0).path("buildingId").asInt()).isNotEqualTo(buildingId);
        assertThat(data(call("GET", "/buildings/" + buildingId + "/rooms?page=1&size=1", null, null))
                .path("totalElements").asLong()).isZero();

        data(call("POST", "/users/me/asset-claims", token(f.owner()), Map.of("resourceType", "BUILDING",
                "resourceId", buildingId, "proofReference", "synthetic-proof-building-" + buildingId)));
        JsonNode claim = only(findBy(data(call("GET", "/users/me/asset-claims", token(f.owner()), null)),
                "resourceId", buildingId));
        assertThat(claim.path("origin").asText()).isEqualTo("CREATED");
        assertThat(claim.path("status").asText()).isEqualTo("PENDING");
        assertStatus(call("GET", "/buildings/" + buildingId, null, null), 404);
        data(call("POST", "/admin/asset-claims/BUILDING/" + positiveId(claim, "membershipId") + "/approve",
                staffToken(), Map.of("reviewNote", "Synthetic property-specific ownership confirmed")));
        JsonNode published = data(call("GET", "/buildings/" + buildingId, null, null));
        assertThat(published.path("buildingId").asInt()).isEqualTo(buildingId);
        assertThat(published.hasNonNull("buildingLessorRrn")).isFalse();
        assertStatus(call("GET", "/rooms/" + draftRoom, null, null), 200);
        assertThat(data(call("GET", "/buildings?page=1&size=1", null, null)).path("totalElements").asLong())
                .isEqualTo(beforePublic + 1);
    }

    @Test
    void aDraftWithoutPropertyProofCannotBePublishedByAnAccountReview() throws Exception {
        int id = positiveId(data(upload("POST", "/owner/buildings", buildingFields("Unproved draft"),
                null, null, token(f.owner()))), "buildingId");
        int membership = jdbc.queryForObject("SELECT membership_id FROM building_memberships WHERE building_id=?",
                Integer.class, id);
        assertStatus(call("POST", "/admin/asset-claims/BUILDING/" + membership + "/approve", staffToken(),
                Map.of("reviewNote", "No property evidence has been attached")), 400);
        assertThat(jdbc.queryForObject("SELECT state FROM building_memberships WHERE membership_id=?",
                String.class, membership)).isEqualTo("PENDING");
        assertStatus(call("GET", "/buildings/" + id, null, null), 404);
    }

    @ParameterizedTest
    @ValueSource(strings = {"BUILDING", "ROOM"})
    void staleAssetReviewLeavesTheCurrentProofAndDecisionUntouched(String type) throws Exception {
        int id = new TransactionTemplate(transactions).execute(status -> {
            Building asset = building(buildings, "Synthetic legacy asset");
            return "BUILDING".equals(type) ? asset.getBuildingId() : room(rooms, asset, 903).getRoomId();
        });
        String owner = token(f.owner());
        String path = "/users/me/asset-claims";
        JsonNode claim = data(call("POST", path, owner,
                Map.of("resourceType", type, "resourceId", id, "proofReference", "Original synthetic evidence")));
        int membership = positiveId(claim, "membershipId");
        String reviewPath = "/admin/asset-claims/" + type + "/" + membership;
        String ownerPath = ("BUILDING".equals(type) ? "/owner/buildings/" : "/owner/rooms/") + id;
        JsonNode stale = findBy(data(call("GET", "/admin/asset-claims?status=PENDING", staffToken(), null)),
                "membershipId", membership).stream().filter(row -> type.equals(row.path("resourceType").asText())).findFirst().orElseThrow();
        JsonNode current = data(call("POST", path, owner,
                Map.of("resourceType", type, "resourceId", id, "proofReference", "Updated synthetic evidence")));
        assertThat(current.path("updatedAt").asText()).isNotBlank().isNotEqualTo(stale.path("updatedAt").asText());
        State pending = state();
        assertReviewConflict(call("POST", reviewPath + "/approve", staffToken(),
                Map.of("reviewNote", "Reviewed original proof", "expectedUpdatedAt", stale.path("updatedAt"))));
        assertThat(state()).isEqualTo(pending);
        assertStatus(call("GET", ownerPath, owner, null), 403);

        JsonNode approved = data(call("POST", reviewPath + "/approve", staffToken(),
                Map.of("reviewNote", "Reviewed updated proof", "expectedUpdatedAt", current.path("updatedAt"))));
        assertThat(approved.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(approved.path("proofReference").asText()).isEqualTo("Updated synthetic evidence");
        State afterApproval = state();
        assertReviewConflict(call("POST", reviewPath + "/reject", staffToken(),
                Map.of("reviewNote", "Stale opposite decision", "expectedUpdatedAt", current.path("updatedAt"))));
        assertThat(state()).isEqualTo(afterApproval);
        assertStatus(call("GET", ownerPath, owner, null), 200);
    }

    @Test
    void aPendingClaimDoesNotStealAnExistingPropertyOrHideItsPublicListing() throws Exception {
        data(call("POST", "/users/me/asset-claims", token(f.otherOwner()), Map.of("resourceType", "BUILDING",
                "resourceId", f.building(), "proofReference", "synthetic-unapproved-claim")));
        assertStatus(call("GET", "/owner/buildings/" + f.building(), token(f.otherOwner()), null), 403);
        assertStatus(call("GET", "/owner/buildings/" + f.building(), token(f.owner()), null), 200);
        assertStatus(call("GET", "/buildings/" + f.building(), null, null), 200);
        verifyNoInteractions(storage, images, notifications, slack);
    }

    @Test
    void anActiveAssetClaimResubmissionKeepsItsReviewedAccessAndCannotTransferAnotherOwnersBuilding() throws Exception {
        JsonNode existing = data(call("POST", "/users/me/asset-claims", token(f.owner()), Map.of("resourceType", "BUILDING",
                "resourceId", f.building(), "proofReference", "synthetic-updated-proof")));
        assertThat(existing.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(existing.path("proofReference").asText()).isEqualTo("synthetic-proof");
        assertStatus(call("GET", "/owner/buildings/" + f.building(), token(f.owner()), null), 200);
        JsonNode pending = data(call("POST", "/users/me/asset-claims", token(f.otherOwner()), Map.of("resourceType", "BUILDING",
                "resourceId", f.building(), "proofReference", "synthetic-competing-claim")));
        State before = state();
        assertStatus(call("POST", "/admin/asset-claims/BUILDING/" + positiveId(pending, "membershipId") + "/approve",
                staffToken(), Map.of("reviewNote", "Cannot replace another active owner")), 400);
        assertThat(state()).isEqualTo(before);
        assertStatus(call("GET", "/owner/buildings/" + f.building(), token(f.otherOwner()), null), 403);
        verifyNoInteractions(storage, images, notifications, slack);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reviewedCreatedAssetsStayClosedAfterRejectionOrRevocationAndResubmission(boolean previouslyApproved) throws Exception {
        String oldToken = token(f.owner());
        int id = positiveId(data(upload("POST", "/owner/buildings", buildingFields("Revocable publication"),
                null, null, oldToken)), "buildingId");
        JsonNode claim = data(call("POST", "/users/me/asset-claims", oldToken, Map.of("resourceType", "BUILDING",
                "resourceId", id, "proofReference", "synthetic-property-proof")));
        int membership = positiveId(claim, "membershipId");
        if (previouslyApproved) {
            data(call("POST", "/admin/asset-claims/BUILDING/" + membership + "/approve", staffToken(),
                    Map.of("reviewNote", "Synthetic proof checked")));
        }
        assertStatus(call("GET", "/buildings/" + id, null, null), previouslyApproved ? 200 : 404);
        assertStatus(call("GET", "/owner/buildings/" + id, oldToken, null), 200);
        data(call("POST", "/admin/asset-claims/BUILDING/" + membership + "/reject", staffToken(),
                Map.of("reviewNote", "Synthetic asset authority revoked")));
        assertThat(roleNames(me(oldToken))).contains("CUSTOMER", "OWNER");
        assertStatus(call("GET", "/buildings/" + id, null, null), 404);
        assertStatus(call("GET", "/owner/buildings/" + id, oldToken, null), 403);
        assertThat(jdbc.queryForObject("SELECT state FROM building_memberships WHERE membership_id=?", String.class,
                membership)).isEqualTo(previouslyApproved ? "REVOKED" : "REJECTED");
        JsonNode resubmitted = data(call("POST", "/users/me/asset-claims", oldToken, Map.of("resourceType", "BUILDING",
                "resourceId", id, "proofReference", "synthetic-revised-title-proof")));
        assertThat(resubmitted.path("status").asText()).isEqualTo("PENDING");
        assertThat(resubmitted.path("origin").asText()).isEqualTo("CREATED");
        assertThat(positiveId(resubmitted, "membershipId")).isEqualTo(membership);
        State before = state();
        assertStatus(call("GET", "/buildings/" + id, null, null), 404);
        assertStatus(call("GET", "/owner/buildings/" + id, oldToken, null), 403);
        assertStatus(upload("PUT", "/owner/buildings/" + id, buildingFields("Unauthorized edit"),
                null, null, oldToken), 403);
        assertThat(state()).isEqualTo(before);
        data(call("POST", "/admin/asset-claims/BUILDING/" + membership + "/approve", staffToken(),
                Map.of("reviewNote", "Synthetic revised proof checked")));
        assertStatus(call("GET", "/buildings/" + id, null, null), 200);
        assertStatus(call("GET", "/owner/buildings/" + id, oldToken, null), 200);
    }

    @Test
    void aUnitClaimChangesOnlyItsRoomAfterStaffApprovalAndRevocation() throws Exception {
        String originalOwner = token(f.owner());
        String unitOwner = token(f.otherOwner());
        JsonNode claim = data(call("POST", "/users/me/asset-claims", unitOwner, Map.of("resourceType", "ROOM",
                "resourceId", f.room(), "proofReference", "synthetic-unit-title-proof")));
        int membership = positiveId(claim, "membershipId");
        assertThat(claim.path("status").asText()).isEqualTo("PENDING");
        assertStatus(call("GET", "/owner/rooms/" + f.room(), unitOwner, null), 403);
        assertStatus(call("GET", "/owner/rooms/" + f.room(), originalOwner, null), 200);
        assertThat(findBy(data(call("GET", "/admin/asset-claims?status=PENDING", staffToken(), null)),
                "membershipId", membership)).anySatisfy(row -> assertThat(row.path("resourceType").asText()).isEqualTo("ROOM"));
        data(call("POST", "/admin/asset-claims/ROOM/" + membership + "/approve", staffToken(),
                Map.of("reviewNote", "Synthetic unit ownership checked")));
        JsonNode unit = data(call("GET", "/owner/rooms/" + f.room(), unitOwner, null));
        assertThat(unit.hasNonNull("buildingLessorRrn")).isFalse();
        assertStatus(call("GET", "/owner/rooms/" + f.room(), originalOwner, null), 403);
        assertStatus(call("GET", "/owner/buildings/" + f.building(), unitOwner, null), 403);
        data(call("POST", "/admin/asset-claims/ROOM/" + membership + "/reject", staffToken(),
                Map.of("reviewNote", "Synthetic unit authorization revoked")));
        assertStatus(call("GET", "/owner/rooms/" + f.room(), unitOwner, null), 403);
        assertStatus(call("GET", "/owner/rooms/" + f.room(), originalOwner, null), 200);
        assertThat(roleNames(me(unitOwner))).contains("CUSTOMER", "OWNER");
        verifyNoInteractions(storage, images, notifications, slack);
    }

    @Test
    void assetApprovalCannotRestoreAnOwnerWhoseAccountQualificationWasRevoked() throws Exception {
        int id = positiveId(data(upload("POST", "/owner/buildings", buildingFields("Eligibility revoked draft"),
                null, null, token(f.owner()))), "buildingId");
        JsonNode claim = data(call("POST", "/users/me/asset-claims", token(f.owner()), Map.of("resourceType", "BUILDING",
                "resourceId", id, "proofReference", "synthetic-property-proof")));
        data(call("POST", "/admin/owner-applications/" + f.owner() + "/reject", staffToken(),
                Map.of("reviewNote", "Synthetic account eligibility revoked")));
        State before = state();
        assertStatus(call("POST", "/admin/asset-claims/BUILDING/" + positiveId(claim, "membershipId") + "/approve",
                staffToken(), Map.of("reviewNote", "Account approval is independently required")), 403);
        assertThat(state()).isEqualTo(before);
        assertStatus(call("GET", "/buildings/" + id, null, null), 404);
        assertThat(roleNames(me(token(f.owner())))).contains("CUSTOMER").doesNotContain("OWNER");
        verifyNoInteractions(storage, images, notifications, slack);
    }

    @Test
    void legacyUnassignedPropertyIsPublicButNeverImplicitlyOwnedByItsLessorStrings() throws Exception {
        Building legacy = new TransactionTemplate(transactions).execute(status -> building(buildings, "Legacy unassigned"));
        int id = legacy.getBuildingId();
        jdbc.update("UPDATE building SET building_lessor_nm=(SELECT user_nm FROM users WHERE user_id=?) WHERE building_id=?",
                f.owner(), id);
        JsonNode publicDetail = data(call("GET", "/buildings/" + id, null, null));
        assertThat(publicDetail.hasNonNull("buildingLessorNm")).isFalse();
        assertThat(publicDetail.hasNonNull("buildingLessorTel")).isFalse();
        assertThat(publicDetail.hasNonNull("buildingLessorAddr")).isFalse();
        assertThat(publicDetail.hasNonNull("buildingLessorRrn")).isFalse();
        assertStatus(call("GET", "/owner/buildings/" + id, token(f.owner()), null), 403);
        assertStatus(upload("PUT", "/owner/buildings/" + id, Map.of("buildingNm", "Forbidden ownership guess"),
                null, null, token(f.owner())), 403);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM building_memberships WHERE building_id=?",
                Integer.class, id)).isZero();
    }

    @Test
    void publicLegacyUnassignedRoomCannotAcceptANewRentalWithoutAVerifiedOperator() throws Exception {
        int roomId = new TransactionTemplate(transactions).execute(status -> {
            Building legacy = building(buildings, "Legacy read only rental");
            return room(rooms, legacy, 903).getRoomId();
        });
        JsonNode verification = data(call("GET", "/rooms/" + roomId, null, null)).path("verifiedRentalOwner");
        assertThat(verification.isBoolean()).isTrue();
        assertThat(verification.asBoolean()).isFalse();
        State before = state();
        assertStatus(upload("POST", "/contracts", contractFields(roomId), "signFile", PNG, token(f.customer())), 400);
        assertThat(state()).isEqualTo(before);
        verifyNoInteractions(storage, images, notifications, slack);
    }

    @Test
    void ownerListsAndCountsAreScopedBeforePagination() throws Exception {
        JsonNode a = data(call("GET", "/owner/buildings?page=1&size=1", token(f.owner()), null));
        JsonNode b = data(call("GET", "/owner/buildings?page=1&size=1", token(f.otherOwner()), null));
        assertThat(a.path("totalElements").asLong()).isEqualTo(1);
        assertThat(b.path("totalElements").asLong()).isEqualTo(1);
        assertThat(a.path("content").size()).isEqualTo(1);
        assertThat(a.path("content").get(0).path("buildingId").asInt()).isEqualTo(f.building());
        assertThat(b.path("content").get(0).path("buildingId").asInt()).isEqualTo(f.otherBuilding());
        JsonNode rooms = data(call("GET", "/owner/rooms?page=1&size=1", token(f.owner()), null));
        assertThat(rooms.path("totalElements").asLong()).isEqualTo(1);
        assertThat(rooms.path("content").get(0).path("roomId").asInt()).isEqualTo(f.room());
    }

    @Test
    void crossOwnerRoomCreationAndReparentingFailBeforeWritesOrStorage() throws Exception {
        State before = state();
        Map<String, String> create = roomFields(f.otherBuilding(), f.otherBuildingName(), 902);
        assertStatus(upload("POST", "/owner/rooms", create, null, null, token(f.owner())), 403);
        Map<String, String> update = new LinkedHashMap<>();
        update.put("buildingId", String.valueOf(f.otherBuilding()));
        update.put("buildingNm", f.otherBuildingName());
        assertStatus(upload("PUT", "/owner/rooms/" + f.room(), update, null, null, token(f.owner())), 403);
        assertThat(jdbc.queryForObject("SELECT building_id FROM rooms WHERE room_id=?", Integer.class, f.room()))
                .isEqualTo(f.building());
        assertThat(state()).isEqualTo(before);
        verifyNoInteractions(storage, images, notifications, slack);
    }

    @ParameterizedTest
    @ValueSource(strings = {"building-read", "building-update", "building-delete", "room-read", "room-update",
            "room-delete", "room-status"})
    void crossOwnerPropertyCrudAndStatusFailBeforeWritesOrStorage(String operation) throws Exception {
        State before = state();
        Reply denied = switch (operation) {
            case "building-read" -> call("GET", "/owner/buildings/" + f.otherBuilding(), token(f.owner()), null);
            case "building-update" -> upload("PUT", "/owner/buildings/" + f.otherBuilding(),
                    Map.of("buildingNm", "Untrusted mutation"), "files", PNG, token(f.owner()));
            case "building-delete" -> call("DELETE", "/owner/buildings/" + f.otherBuilding(), token(f.owner()), null);
            case "room-read" -> call("GET", "/owner/rooms/" + f.otherRoom(), token(f.owner()), null);
            case "room-update" -> upload("PUT", "/owner/rooms/" + f.otherRoom(),
                    Map.of("roomDesc", "Untrusted mutation"), "files", PNG, token(f.owner()));
            case "room-delete" -> call("DELETE", "/owner/rooms/" + f.otherRoom(), token(f.owner()), null);
            case "room-status" -> call("PUT", "/owner/rooms/" + f.otherRoom() + "/status", token(f.owner()),
                    Map.of("roomStatus", "repair"));
            default -> throw new AssertionError(operation);
        };
        assertStatus(denied, 403);
        assertThat(state()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT building_nm FROM building WHERE building_id=?", String.class,
                f.otherBuilding())).isEqualTo(f.otherBuildingName());
        assertThat(jdbc.queryForObject("SELECT delete_yn FROM building WHERE building_id=?", String.class,
                f.otherBuilding())).isEqualTo("N");
        assertThat(jdbc.queryForObject("SELECT room_st FROM rooms WHERE room_id=?", String.class, f.otherRoom()))
                .isEqualTo("available");
        assertThat(jdbc.queryForObject("SELECT delete_yn FROM rooms WHERE room_id=?", String.class, f.otherRoom()))
                .isEqualTo("N");
        assertThat(jdbc.queryForObject("SELECT room_desc FROM rooms WHERE room_id=?", String.class, f.otherRoom())).isNull();
        verifyNoInteractions(storage, images, notifications, slack);
    }

    @Test
    void explicitUnitOwnershipOverridesBuildingInheritanceWithoutGrantingBuildingManagementOrPii() throws Exception {
        TestOwnershipFixtures.roomOwner(jdbc, f.otherOwner(), f.room());
        assertStatus(call("GET", "/owner/rooms/" + f.room(), token(f.owner()), null), 403);
        JsonNode unit = data(call("GET", "/owner/rooms/" + f.room(), token(f.otherOwner()), null));
        assertThat(unit.path("roomId").asInt()).isEqualTo(f.room());
        assertThat(unit.hasNonNull("buildingLessorNm")).isFalse();
        assertThat(unit.hasNonNull("buildingLessorTel")).isFalse();
        assertThat(unit.hasNonNull("buildingLessorAddr")).isFalse();
        assertThat(unit.hasNonNull("buildingLessorRrn")).isFalse();
        assertThat(unit.path("buildingManagementAllowed").asBoolean(true)).isFalse();
        assertThat(unit.path("canReassignBuilding").asBoolean(true)).isFalse();
        JsonNode edited = data(upload("PUT", "/owner/rooms/" + f.room(),
                Map.of("buildingId", String.valueOf(f.building()), "buildingNm", f.buildingName(),
                        "roomDesc", "Synthetic unit owner's edit"), null, null, token(f.otherOwner())));
        assertThat(edited.path("roomDesc").asText()).isEqualTo("Synthetic unit owner's edit");
        assertThat(edited.hasNonNull("buildingLessorRrn")).isFalse();
        JsonNode unitList = data(call("GET", "/owner/rooms?page=1&size=1&buildingId=" + f.building(),
                token(f.otherOwner()), null));
        assertThat(unitList.path("totalElements").asLong()).isEqualTo(1);
        assertThat(unitList.path("content").get(0).path("roomId").asInt()).isEqualTo(f.room());
        assertStatus(upload("PUT", "/owner/rooms/" + f.room(),
                Map.of("buildingId", String.valueOf(f.otherBuilding()), "buildingNm", f.otherBuildingName()),
                null, null, token(f.otherOwner())), 403);
        assertThat(jdbc.queryForObject("SELECT building_id FROM rooms WHERE room_id=?", Integer.class, f.room()))
                .isEqualTo(f.building());
        assertStatus(call("GET", "/owner/buildings/" + f.building(), token(f.otherOwner()), null), 403);
        State before = state();
        assertStatus(call("DELETE", "/owner/buildings/" + f.building(), token(f.owner()), null), 403);
        assertThat(state()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT delete_yn FROM building WHERE building_id=?", String.class,
                f.building())).isEqualTo("N");
        assertThat(jdbc.queryForObject("SELECT delete_yn FROM rooms WHERE room_id=?", String.class,
                f.room())).isEqualTo("N");
        verifyNoInteractions(storage, images, notifications, slack);
    }

    @Test
    void aPendingCreatedDraftFileIsReadableOnlyByItsOwnerAndNeverReadsStorageForDeniedActors() throws Exception {
        int id = positiveId(data(upload("POST", "/owner/buildings", buildingFields("Private file draft"),
                null, null, token(f.owner()))), "buildingId");
        int file = seedFile("BUILDING", id);
        clearInvocations(storage, images, notifications, slack);
        Reply anonymous = call("GET", "/files/" + file + "/view", null, null);
        assertThat(anonymous.status()).isBetween(400, 499);
        assertThat(Arrays.equals(anonymous.body(), PNG)).isFalse();
        assertStatus(call("GET", "/files/" + file + "/view", token(f.otherOwner()), null), 403);
        verifyNoInteractions(storage, images, notifications, slack);
        Reply permitted = call("GET", "/files/" + file + "/view", token(f.owner()), null);
        assertStatus(permitted, 200);
        assertThat(permitted.body()).isEqualTo(PNG);
    }

    @ParameterizedTest
    @ValueSource(strings = {"BUILDING", "ROOM"})
    void publishedPropertyFileMetadataAlwaysUsesTheAuthorizedProxyEvenWithS3Storage(String type) throws Exception {
        int parent = type.equals("BUILDING") ? f.building() : f.room();
        int file = seedFile(type, parent);
        JsonNode metadata = data(call("GET", "/files/" + file, null, null));
        assertThat(metadata.path("viewUrl").asText()).isEqualTo("/files/" + file + "/view");
        assertThat(metadata.path("downloadUrl").asText()).isEqualTo("/files/" + file + "/download");
        assertThat(metadata.path("adminViewUrl").asText()).isEqualTo("/files/" + file + "/view");
        assertThat(metadata.path("adminDownloadUrl").asText()).isEqualTo("/files/" + file + "/download");
        verifyNoInteractions(storage, images, notifications, slack);
        Reply publicImage = call("GET", "/files/" + file + "/view", null, null);
        assertStatus(publicImage, 200);
        assertThat(publicImage.body()).isEqualTo(PNG);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/static-files/BUILDING/synthetic.png", "/static-files/room/synthetic.png",
            "/static-files/Space/synthetic.png", "/static-files/CONTRACT/synthetic.png",
            "/static-files/BUILDING", "/static-files/room?download=true",
            "/static-files/SPACE/deep/synthetic.png?download=true", "/static-files/contract/deep/synthetic.png"})
    void rawPropertyAndContractStaticPathsCannotBypassTheirFilePolicies(String path) throws Exception {
        Path realFile = UPLOAD_ROOT.resolve("BUILDING/synthetic.png");
        Files.createDirectories(realFile.getParent());
        Files.write(realFile, PNG);
        for (String method : List.of("GET", "HEAD", "POST", "DELETE")) {
            Reply denied = call(method, path, token(f.owner()), null);
            assertStatus(denied, 403);
            assertThat(Arrays.equals(denied.body(), PNG)).isFalse();
        }
        verifyNoInteractions(storage, images, notifications, slack);
    }

    @Test
    void staffAndOwnersCannotUseTheLegacyGlobalFileAdministrationBypass() throws Exception {
        int document = seedFile("ROOM", f.otherRoom());
        State before = state();
        for (String actor : List.of(staffToken(), token(f.owner()))) {
            assertStatus(call("GET", "/files/admin/" + document + "/view", actor, null), 403);
            assertStatus(call("GET", "/files/admin?fileParentType=ROOM&fileParentId=" + f.otherRoom(), actor, null), 403);
            assertStatus(call("DELETE", "/files/admin", actor, List.of(document)), 403);
        }
        assertThat(state()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT delete_yn FROM files WHERE file_id=?", String.class, document)).isEqualTo("N");
        verifyNoInteractions(storage, images, notifications, slack);
    }

    @Test
    void mixedOwnAndForeignFileDeletionIsRejectedBeforeAnyRowChanges() throws Exception {
        int own = seedFile("ROOM", f.room());
        int foreign = seedFile("ROOM", f.otherRoom());
        clearInvocations(storage, images, notifications, slack);
        assertStatus(call("DELETE", "/owner/files", token(f.owner()), List.of(own, foreign)), 403);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM files WHERE file_id IN (?,?) AND delete_yn='N'",
                Integer.class, own, foreign)).isEqualTo(2);
        verifyNoInteractions(storage, images, notifications, slack);
        data(call("DELETE", "/owner/files", token(f.owner()), List.of(own)));
        assertThat(jdbc.queryForObject("SELECT delete_yn FROM files WHERE file_id=?", String.class, own)).isEqualTo("Y");
        assertThat(jdbc.queryForObject("SELECT delete_yn FROM files WHERE file_id=?", String.class, foreign)).isEqualTo("N");
    }

    @Test
    void commonPropertyFileUploadCannotBypassForeignOwnership() throws Exception {
        State before = state();
        assertStatus(upload("POST", "/files", Map.of("fileParentType", "ROOM", "fileParentId", String.valueOf(f.otherRoom())),
                "files", PNG, token(f.owner())), 403);
        assertThat(state()).isEqualTo(before);
        verifyNoInteractions(storage, images, notifications, slack);
    }

    @Test
    void anOwnerCannotApplyAsCustomerForTheirOwnRoom() throws Exception {
        State before = state();
        assertStatus(upload("POST", "/contracts", contractFields(f.room()), "signFile", PNG,
                token(f.owner())), 403);
        assertThat(state()).isEqualTo(before);
        verifyNoInteractions(storage, images, notifications, slack);
    }

    @Test
    void theSameOwnerAccountCanApplyAsCustomerAtAnotherOwnersPropertyAndRecordsExplicitContractParties() throws Exception {
        JsonNode result = data(upload("POST", "/contracts", contractFields(f.otherRoom()), "signFile", PNG,
                token(f.owner())));
        int id = positiveId(result, "contractId");
        assertThat(result.path("roomId").asInt()).isEqualTo(f.otherRoom());
        assertThat(result.path("contractStatus").asText()).isEqualTo("requested");
        assertThat(jdbc.queryForObject("SELECT user_id FROM contract WHERE contract_id=?", String.class, id))
                .isEqualTo(f.owner());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM contract_owner_parties WHERE contract_id=? AND user_id=?",
                Integer.class, id, f.otherOwner())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM contract_owner_parties WHERE contract_id=? AND user_id=?",
                Integer.class, id, f.staff())).isZero();
        assertThat(roleNames(me(token(f.owner())))).contains("CUSTOMER", "OWNER");
        String inbox = "/owner/contracts?page=1&size=1&contractId=" + id;
        JsonNode recipientPage = data(call("GET", inbox, token(f.otherOwner()), null));
        assertThat(recipientPage.path("totalElements").asLong()).isEqualTo(1);
        assertThat(recipientPage.path("content").get(0).path("contractId").asInt()).isEqualTo(id);
        assertThat(data(call("GET", inbox, token(f.owner()), null)).path("totalElements").asLong()).isZero();
        int document = positiveId(result, "contractPdfFileId");
        assertStatus(call("GET", "/files/" + document + "/view", token(f.owner()), null), 200);
        assertStatus(call("GET", "/files/" + document + "/view", token(f.otherOwner()), null), 200);
        assertStatus(call("GET", "/files/" + document + "/view", staffToken(), null), 403);
    }

    @Test
    void manageableScopeSelectCountDoesNotGrowWithUnrelatedInventory() {
        AuthUser principal = new AuthUser(f.owner(), "user", "MEMBER", Set.of("CUSTOMER", "USER", "OWNER"), "APPROVED");
        SecurityContext previousContext = SecurityContextHolder.getContext();
        SecurityContext ownerContext = SecurityContextHolder.createEmptyContext();
        ownerContext.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities()));
        SecurityContextHolder.setContext(ownerContext);
        try {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                status.setRollbackOnly();
                User customer = users.findById(f.customer()).orElseThrow();
                Room ownedRoom = rooms.findById(f.room()).orElseThrow();
                int ownedContractId = contract(customer, ownedRoom, ContractStatus.requested).getContractId();
                TestOwnershipFixtures.contractParty(jdbc, f.owner(), ownedContractId);
                ManageableScope expected = new ManageableScope(Set.of(f.building()), Set.of(f.room()), Set.of(ownedContractId));

                long beforeBaseline = sessionSelects();
                ManageableScope baseline = manageableScope();
                long baselineSelects = sessionSelects() - beforeBaseline;
                assertThat(baseline).isEqualTo(expected);
                assertThat(baselineSelects).isPositive();

                List<Integer> readableContractIds = new ArrayList<>(List.of(ownedContractId));
                for (int i = 0; i < 16; i++) {
                    Building foreignBuilding = building(buildings, "Unassigned inventory");
                    Room foreignRoom = room(rooms, foreignBuilding, 901);
                    int historicalContractId = contract(customer, foreignRoom, ContractStatus.ended).getContractId();
                    TestOwnershipFixtures.contractParty(jdbc, f.owner(), historicalContractId);
                    readableContractIds.add(historicalContractId);
                }

                long beforeExpandedScope = sessionSelects();
                ManageableScope expandedScope = manageableScope();
                long expandedSelects = sessionSelects() - beforeExpandedScope;
                assertThat(expandedScope).isEqualTo(expected);
                assertThat(ownership.readableContractIds()).containsExactlyInAnyOrderElementsOf(readableContractIds);
                assertThat(expandedSelects).as("SELECTs for all three manageable scopes").isEqualTo(baselineSelects);
            });
        } finally {
            SecurityContextHolder.setContext(previousContext);
        }
    }

    @Test
    void currentPropertyOwnershipDoesNotAutomaticallyGrantLegacyContractHistory() throws Exception {
        int[] ids = new TransactionTemplate(transactions).execute(status -> {
            Contract old = contract(users.findById(f.customer()).orElseThrow(), rooms.findById(f.room()).orElseThrow(),
                    ContractStatus.ended);
            UploadFile document = file(files, "CONTRACT", old.getContractId());
            old.setContractPdfFileId(document.getFileId());
            contracts.flush();
            return new int[] {old.getContractId(), document.getFileId()};
        });
        assertStatus(call("GET", "/owner/contracts/" + ids[0], token(f.owner()), null), 403);
        assertStatus(call("GET", "/files/" + ids[1] + "/view", token(f.owner()), null), 403);
        assertStatus(call("GET", "/files/" + ids[1] + "/view", staffToken(), null), 403);
        verifyNoInteractions(storage, images, notifications, slack);
        assertStatus(call("GET", "/files/" + ids[1] + "/view", token(f.customer()), null), 200);
    }

    @Test
    void contractPartySnapshotsPreserveOriginalHistoryWithoutGivingANewPropertyOwnerPrivateDocuments() throws Exception {
        JsonNode created = data(upload("POST", "/contracts", contractFields(f.otherRoom()), "signFile", PNG,
                token(f.customer())));
        int contract = positiveId(created, "contractId");
        int document = positiveId(created, "contractPdfFileId");
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.update("UPDATE building_memberships SET state='REVOKED' WHERE user_id=? AND building_id=?",
                    f.otherOwner(), f.otherBuilding());
            TestOwnershipFixtures.buildingOwner(jdbc, f.applicant(), f.otherBuilding());
        });
        clearInvocations(storage, images, notifications, slack);
        assertStatus(call("GET", "/files/" + document + "/view", token(f.applicant()), null), 403);
        assertStatus(upload("PUT", "/owner/contracts/" + contract, Map.of("contractStatus", "approved"),
                null, null, token(f.otherOwner())), 403);
        assertThat(jdbc.queryForList("SELECT user_id FROM contract_owner_parties WHERE contract_id=?", String.class,
                contract)).containsExactly(f.otherOwner());
        assertThat(jdbc.queryForObject("SELECT contract_st FROM contract WHERE contract_id=?", String.class, contract))
                .isEqualTo("requested");
        verifyNoInteractions(storage, images, notifications, slack);
        assertStatus(call("GET", "/files/" + document + "/view", token(f.otherOwner()), null), 200);
        assertStatus(call("GET", "/files/" + document + "/view", token(f.customer()), null), 200);
    }

    @Test
    void movingAPreviouslyReviewedRoomIntoAnUnpublishedDraftHidesPublicReviewsAndMasksPrivatePropertyMetadata() throws Exception {
        long before = data(call("GET", "/reviews?page=0&size=1", null, null)).path("totalElements").asLong();
        new TransactionTemplate(transactions).executeWithoutResult(status -> contract(users.findById(f.customer()).orElseThrow(),
                rooms.findById(f.room()).orElseThrow(), ContractStatus.ended));
        data(upload("POST", "/reviews", Map.of("roomId", String.valueOf(f.room()), "rating", "5",
                "reviewTitle", "Synthetic historical review", "reviewCtnt", "Synthetic private review text"),
                null, null, token(f.customer())));
        int reviewId = jdbc.queryForObject("SELECT review_id FROM reviews WHERE user_id=? AND room_id=?", Integer.class,
                f.customer(), f.room());
        JsonNode publicBefore = data(call("GET", "/reviews?page=0&size=1", null, null));
        assertThat(publicBefore.path("totalElements").asLong()).isEqualTo(before + 1);
        assertThat(publicBefore.path("content").get(0).path("reviewId").asInt()).isEqualTo(reviewId);
        assertStatus(call("GET", "/reviews/" + reviewId + "?increaseReadCount=false", null, null), 200);

        int draft = positiveId(data(upload("POST", "/owner/buildings", buildingFields("Private reviewed room draft"),
                null, null, token(f.owner()))), "buildingId");
        data(upload("PUT", "/owner/rooms/" + f.room(), Map.of("buildingId", String.valueOf(draft)),
                null, null, token(f.owner())));
        clearInvocations(storage, images, notifications, slack);
        JsonNode publicAfter = data(call("GET", "/reviews?page=0&size=1", null, null));
        assertThat(publicAfter.path("totalElements").asLong()).isEqualTo(before);
        if (!publicAfter.path("content").isEmpty()) {
            assertThat(publicAfter.path("content").get(0).path("reviewId").asInt()).isNotEqualTo(reviewId);
        }
        clearInvocations(storage, images, notifications, slack);
        assertStatus(call("GET", "/reviews/" + reviewId, null, null), 404);
        assertStatus(call("GET", "/reviews/rooms/" + f.room() + "/summary", null, null), 404);
        JsonNode own = only(findBy(data(call("GET", "/reviews/my?page=0&size=10", token(f.customer()), null))
                .path("content"), "reviewId", reviewId));
        assertThat(own.path("reviewCtnt").asText()).isEqualTo("Synthetic private review text");
        assertThat(own.hasNonNull("buildingId")).isFalse();
        assertThat(own.hasNonNull("buildingNm")).isFalse();
        assertThat(own.hasNonNull("roomNo")).isFalse();
        assertThat(jdbc.queryForObject("SELECT read_count FROM reviews WHERE review_id=?", Integer.class, reviewId)).isZero();
        verifyNoInteractions(storage, images, notifications, slack);
    }

    private JsonNode me(String token) throws Exception { return data(call("GET", "/users/me", token, null)); }

    private int activeTenancy(String userId, int roomId) {
        return new TransactionTemplate(transactions).execute(status -> {
            Room room = rooms.findById(roomId).orElseThrow();
            room.setRoomSt(RoomStatus.contracted);
            Contract active = contract(users.findById(userId).orElseThrow(), room, ContractStatus.active);
            residents.saveAndFlush(Resident.builder().buildingId(room.getBuilding().getBuildingId())
                    .contractId(active.getContractId()).userId(userId).build());
            return active.getContractId();
        });
    }

    private int seedFile(String type, int parentId) {
        return new TransactionTemplate(transactions).execute(status -> file(files, type, parentId).getFileId());
    }

    private String token(String userId) { return jwt.createAccessToken(userId, "user"); }
    private String staffToken() { return jwt.createAccessToken(f.staff(), "admin"); }

    private State state() {
        return new State(count("building"), count("rooms"), count("files"), count("contract"), count("residents"),
                count("owner_profiles"), count("account_role_grants"), count("building_memberships"),
                count("room_memberships"), count("contract_owner_parties"), count("ownership_review_events"));
    }

    private long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }

    private Reply call(String method, String path, String token, Object body) throws Exception {
        byte[] bytes = body == null ? null : json.writeValueAsBytes(body);
        return request(method, path, token, bytes, bytes == null ? null : "application/json");
    }

    private Reply upload(String method, String path, Map<String, String> fields, String fileField,
            byte[] bytes, String token) throws Exception {
        String boundary = "SyntheticOwnershipBoundary" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (var field : fields.entrySet()) {
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field.getKey()
                    + "\"\r\n\r\n" + field.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        if (bytes != null) {
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + fileField
                    + "\"; filename=\"synthetic-signature.png\"\r\nContent-Type: image/png\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            body.write(bytes);
            body.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return request(method, path, token, body.toByteArray(), "multipart/form-data; boundary=" + boundary);
    }

    private Reply request(String method, String path, String token, byte[] body, String contentType) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api" + path))
                .timeout(Duration.ofSeconds(20)).header("Accept", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (contentType != null) request.header("Content-Type", contentType);
        HttpResponse<byte[]> response = HTTP.send(request.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        return new Reply(response.statusCode(), response.body());
    }

    private JsonNode data(Reply response) throws IOException {
        assertStatus(response, 200);
        JsonNode value = json.readTree(response.body());
        assertThat(value.path("success").asBoolean()).isTrue();
        return value.path("data");
    }

    private static void assertStatus(Reply response, int expected) {
        assertThat(response.status()).as("HTTP status; response=%s", new String(response.body(), StandardCharsets.UTF_8))
                .isEqualTo(expected);
    }

    private void assertReviewConflict(Reply response) throws IOException {
        assertStatus(response, 409);
        assertThat(json.readTree(response.body()).path("errorCode").asText()).isEqualTo("OWNERSHIP_409");
    }
    private static int positiveId(JsonNode node, String key) { int id = node.path(key).asInt(); assertThat(id).isPositive(); return id; }
    private static JsonNode only(List<JsonNode> values) { assertThat(values).hasSize(1); return values.get(0); }

    private static List<JsonNode> findBy(JsonNode array, String key, Object value) {
        assertThat(array.isArray()).isTrue();
        List<JsonNode> result = new ArrayList<>();
        array.forEach(node -> {
            if (value instanceof Number n ? node.path(key).asLong() == n.longValue()
                    : node.path(key).asText().equals(String.valueOf(value))) result.add(node);
        });
        return result;
    }

    private static List<String> roleNames(JsonNode profile) {
        assertThat(profile.path("roles").isArray()).isTrue();
        List<String> roles = new ArrayList<>();
        profile.path("roles").forEach(role -> roles.add(role.asText()));
        return roles;
    }

    private static Map<String, String> buildingFields(String name) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("buildingNm", name + " " + UUID.randomUUID().toString().substring(0, 8));
        result.put("buildingAddr", "Synthetic property address");
        result.put("buildingLessorNm", "Synthetic private landlord");
        result.put("buildingLessorTel", "010-0000-0000");
        result.put("buildingLessorAddr", "Synthetic private owner address");
        result.put("buildingLessorRrn", "000000-0000000");
        return result;
    }

    private static Map<String, String> roomFields(int buildingId, String buildingName, int number) {
        return Map.of("buildingId", String.valueOf(buildingId), "buildingNm", buildingName,
                "roomNo", String.valueOf(number), "floor", "1", "roomSize", "20.00", "rentPrice", "500000");
    }

    private static Map<String, String> contractFields(int roomId) {
        LocalDate start = LocalDate.now(KST).plusDays(2);
        return Map.of("roomId", String.valueOf(roomId), "contractStart", start.toString(),
                "contractEnd", start.plusMonths(1).toString(), "paymentDay", "15", "lessorNm", "Synthetic tenant",
                "lessorTel", "010-0000-0000", "lessorAddr", "Synthetic applicant address", "lessorRrn", "000000-0000000");
    }

    private long sessionSelects() {
        return jdbc.queryForObject("SHOW SESSION STATUS LIKE 'Com_select'", (rs, row) -> rs.getLong(2));
    }

    private ManageableScope manageableScope() {
        return new ManageableScope(ownership.manageableBuildingIds(), ownership.manageableRoomIds(),
                ownership.manageableContractIds());
    }

    private Contract contract(User consumer, Room room, ContractStatus status) {
        LocalDate today = LocalDate.now(KST);
        LocalDate start = status == ContractStatus.ended ? today.minusMonths(2)
                : status == ContractStatus.active ? today.minusDays(2) : today.plusDays(2);
        LocalDate end = status == ContractStatus.ended ? today.minusMonths(1) : today.plusMonths(1);
        return contracts.saveAndFlush(Contract.builder().user(consumer).room(room).contractStart(start).contractEnd(end)
                .deposit(room.getDeposit()).rentPrice(room.getRentPrice()).manageFee(room.getManageFee()).paymentDay(15)
                .contractSt(status).rentType(Contract.RentType.monthly_rent).lessorNm("Synthetic tenant")
                .lessorTel("010-0000-0000").lessorAddr("Synthetic fixture address").lessorRrn("000000-0000000").build());
    }

    private static Building building(BuildingRepository repository, String name) {
        return repository.saveAndFlush(Building.builder().buildingNm(name + " " + UUID.randomUUID().toString().substring(0, 8))
                .buildingAddr("Synthetic property address").buildingLessorNm("Synthetic private landlord")
                .buildingLessorTel("010-0000-0000").buildingLessorAddr("Synthetic private owner address")
                .buildingLessorRrn("000000-0000000").build());
    }

    private static Room room(RoomRepository repository, Building building, int number) {
        return repository.saveAndFlush(Room.builder().building(building).roomNo(number).floor(1)
                .roomSize(new BigDecimal("20.00")).rentPrice(new BigDecimal("500000"))
                .deposit(new BigDecimal("1000000")).manageFee(new BigDecimal("50000"))
                .rentMin(1).roomSt(RoomStatus.available).build());
    }

    private static User user(UserRepository repository, String id, UserRole role) {
        return repository.saveAndFlush(User.builder().userId(id).userNm("Synthetic ownership fixture")
                .userEmail(id + "@example.test").userPwd(PASSWORD_HASH).userBirth(LocalDate.of(2000, 1, 1))
                .userTel("010-0000-0000").userNickname(id).userRole(role)
                .accountKind(role == UserRole.admin ? AccountKind.STAFF : AccountKind.MEMBER).firstSign("N").build());
    }

    private static UploadFile file(UploadFileRepository repository, String type, int parentId) {
        return repository.saveAndFlush(UploadFile.builder().fileParentType(type).fileParentId(parentId)
                .filePath(type + "/" + parentId + "/synthetic").originFilename("synthetic-document.png")
                .renameFilename("synthetic-document.png").fileSize(PNG.length).fileType(".png").sortOrder(0).build());
    }

    private static void configureEffects(StorageService storage, ContractImageService images,
            UploadFileRepository files) throws IOException {
        when(storage.resolveViewUrl(anyInt(), anyString(), anyString())).thenReturn("https://synthetic-storage.invalid/private-object");
        when(storage.resolveDownloadUrl(anyInt(), anyString(), anyString())).thenReturn("https://synthetic-storage.invalid/private-object?download=true");
        when(storage.read(anyString(), anyString())).thenAnswer(call -> new ByteArrayInputStream(PNG));
        when(images.generateAndSave(any(Contract.class))).thenAnswer(call -> {
            Contract contract = call.getArgument(0);
            return file(files, "CONTRACT", contract.getContractId()).getFileId();
        });
    }

    /** Opt-in only: default CI neither executes a browser nor adds a skipped browser case. */
    @AfterAll
    static void optionalOwnerBrowserFixture(@Autowired UserRepository users, @Autowired BuildingRepository buildings,
            @Autowired RoomRepository rooms, @Autowired UploadFileRepository files, @Autowired JdbcTemplate jdbc,
            @Autowired StorageService storage, @Autowired ContractImageService images,
            @Autowired PlatformTransactionManager transactions, @Autowired ObjectMapper json,
            @LocalServerPort int port) throws Exception {
        try {
            String target = System.getenv("UNIPLACE_OWNER_BROWSER_READY");
            if (target == null || target.isBlank()) return;
            configureEffects(storage, images, files);
            Map<String, Object> value = new TransactionTemplate(transactions).execute(status -> {
                User member = user(users, "owner-browser-member", UserRole.user);
                User staff = user(users, "owner-browser-staff", UserRole.admin);
                User customer = user(users, "owner-browser-customer", UserRole.user);
                User other = user(users, "owner-browser-other", UserRole.user);
                Building foreign = building(buildings, "Browser foreign building");
                Room foreignRoom = room(rooms, foreign, 901);
                TestOwnershipFixtures.buildingOwner(jdbc, other.getUserId(), foreign.getBuildingId());
                return Map.<String, Object>of("baseUrl", "http://127.0.0.1:" + port + "/api",
                        "memberId", member.getUserId(), "staffId", staff.getUserId(), "consumerId", customer.getUserId(),
                        "otherOwnerId", other.getUserId(), "otherBuildingId", foreign.getBuildingId(),
                        "otherRoomId", foreignRoom.getRoomId());
            });
            Path ready = Path.of(target).toAbsolutePath();
            Path done = Path.of(ready + ".done");
            if (Files.exists(done, LinkOption.NOFOLLOW_LINKS)) throw new AssertionError("Browser marker already exists");
            Files.createDirectories(ready.getParent());
            Files.writeString(ready, json.writeValueAsString(value), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
            while (!Files.exists(done)) {
                if (System.nanoTime() >= deadline) throw new AssertionError("Owner browser verification timed out");
                Thread.sleep(200);
            }
            assertThat(Files.readString(done)).isEqualTo("passed");
        } finally {
            try (Stream<Path> paths = Files.walk(UPLOAD_ROOT)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static Path temporaryRoot() {
        try { return Files.createTempDirectory("uniplace-owner-fixture-"); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
    }

    private record Fixture(String owner, String otherOwner, String customer, String applicant, String staff,
            int building, int otherBuilding, int room, int otherRoom, String buildingName, String otherBuildingName) {}
    private record ManageableScope(Set<Integer> buildings, Set<Integer> rooms, Set<Integer> contracts) {}
    private record State(long buildings, long rooms, long files, long contracts, long residents,
            long ownerProfiles, long roleGrants, long buildingMemberships, long roomMemberships,
            long contractParties, long reviewEvents) {}
    private record Reply(int status, byte[] body) {}
}
