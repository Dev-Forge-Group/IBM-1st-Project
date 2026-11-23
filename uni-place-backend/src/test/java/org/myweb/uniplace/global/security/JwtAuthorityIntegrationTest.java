package org.myweb.uniplace.global.security;

import static org.myweb.uniplace.support.TestOwnershipFixtures.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.uniplace.domain.contract.api.dto.request.ContractUpdateRequest;
import org.myweb.uniplace.domain.contract.application.ContractImageService;
import org.myweb.uniplace.domain.contract.application.ContractService;
import org.myweb.uniplace.domain.contract.domain.entity.Contract;
import org.myweb.uniplace.domain.contract.domain.entity.Resident;
import org.myweb.uniplace.domain.contract.domain.enums.ContractStatus;
import org.myweb.uniplace.domain.contract.repository.ContractRepository;
import org.myweb.uniplace.domain.contract.repository.ResidentRepository;
import org.myweb.uniplace.domain.file.application.FileService;
import org.myweb.uniplace.domain.notification.application.NotificationService;
import org.myweb.uniplace.domain.property.domain.entity.Building;
import org.myweb.uniplace.domain.property.domain.entity.Room;
import org.myweb.uniplace.domain.property.domain.enums.RoomStatus;
import org.myweb.uniplace.domain.property.repository.BuildingRepository;
import org.myweb.uniplace.domain.property.repository.RoomRepository;
import org.myweb.uniplace.domain.user.domain.entity.RefreshToken;
import org.myweb.uniplace.domain.user.domain.enums.UserRole;
import org.myweb.uniplace.domain.user.repository.RefreshTokenRepository;
import org.myweb.uniplace.domain.user.repository.UserRepository;
import org.myweb.uniplace.global.exception.ErrorCode;
import org.myweb.uniplace.global.slack.SlackNotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real JWT/security-chain requests against committed MySQL roles, including the role-changing workflows. */
@SpringBootTest(properties = {
        "server.servlet.context-path=/api",
        "jwt.secret=jwt-authority-test-secret-with-at-least-32-bytes",
        "jwt.access-exp=3600000", "jwt.refresh-exp=86400000",
        "spring.mail.host=localhost", "spring.mail.port=2525",
        "spring.mail.username=local", "spring.mail.password=local",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.show-sql=false",
        "logging.level.org.hibernate.SQL=WARN", "payment.reconcile.enabled=false",
        "ai.fastapi.base-url=http://127.0.0.1:9", "slack.webhook-url=",
        "contract.status-transition-cron=-"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("jwt-authority-test")
@Import(JwtAuthorityIntegrationTest.AuthorityProbe.class)
@Testcontainers
class JwtAuthorityIntegrationTest {
    private static final String DEVICE = "jwt-authority-test-device";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("jwt_authority").withUsername("jwt_authority_test")
            .withPassword("jwt-authority-test-only");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", MYSQL::getJdbcUrl);
        properties.add("spring.datasource.username", MYSQL::getUsername);
        properties.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtProvider jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired ContractService contracts;
    @Autowired ContractRepository contractRows;
    @Autowired ResidentRepository residents;
    @Autowired BuildingRepository buildings;
    @Autowired RoomRepository rooms;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean NotificationService notifications;
    @MockitoBean SlackNotificationService slack;
    @MockitoBean FileService files;
    @MockitoBean ContractImageService images;

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"user", "tenant"})
    void adminDemotionRevokesTheExistingAccessTokenOnTheNextRequest(UserRole target) throws Exception {
        String id = member(UserRole.admin);
        String oldAccess = jwt.createAccessToken(id, "admin");
        assertIdentity(send(get("/api/admin/users/_jwt-authority"), oldAccess, 200), id, "admin");

        if (target == UserRole.tenant) {
            assertError(patch("/api/admin/users/" + id + "/role").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsBytes(Map.of("userRole", target.name()))), oldAccess, ErrorCode.BAD_REQUEST);
            assertIdentity(send(get("/api/admin/users/_jwt-authority"), oldAccess, 200), id, "admin");
            return;
        }
        changeRole(id, target, oldAccess);

        assertError(get("/api/admin/users/_jwt-authority"), oldAccess, ErrorCode.FORBIDDEN);
        assertError(get("/api/_jwt-authority"), oldAccess, ErrorCode.FORBIDDEN);
        assertIdentity(send(get("/api/buildings/_jwt-authority"), oldAccess, 200), id, "user", List.of());
        assertThat(jwt.getRole(oldAccess)).isEqualTo("admin");
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"admin", "tenant"})
    void aGenericRolePatchCannotConvertMembersToStaffOrGrantTenantAuthority(UserRole target) throws Exception {
        String id = member(UserRole.user);
        String oldAccess = jwt.createAccessToken(id, "user");
        String admin = member(UserRole.admin);
        MockHttpServletRequestBuilder before = target == UserRole.admin
                ? get("/api/admin/users/_jwt-authority") : put("/api/complains/_jwt-authority");
        assertError(before, oldAccess, ErrorCode.FORBIDDEN);

        assertError(patch("/api/admin/users/" + id + "/role").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("userRole", target.name()))),
                jwt.createAccessToken(admin, "admin"), ErrorCode.BAD_REQUEST);
        assertIdentity(send(get("/api/_jwt-authority"), oldAccess, 200), id, "user");
        assertThat(jwt.getRole(oldAccess)).isEqualTo("user");
    }

    @ParameterizedTest
    @EnumSource(value = ContractStatus.class, names = {"ended", "cancelled"})
    void contractDeactivationRemovesTenantAuthorityFromTheExistingToken(ContractStatus target) throws Exception {
        String id = member(UserRole.tenant);
        int contract = activeContract(id);
        String oldAccess = jwt.createAccessToken(id, "tenant");
        assertIdentity(send(put("/api/complains/_jwt-authority"), oldAccess, 200), id, "tenant");

        contracts.updateContractForAdmin(contract, ContractUpdateRequest.builder().contractStatus(target).build());

        assertThat(jdbc.queryForObject("SELECT user_role FROM users WHERE user_id=?", String.class, id)).isEqualTo("tenant");
        assertThat(jdbc.queryForObject("SELECT contract_st FROM contract WHERE contract_id=?", String.class, contract))
                .isEqualTo(target.name());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM residents WHERE contract_id=?", Integer.class, contract)).isZero();
        assertError(put("/api/complains/_jwt-authority"), oldAccess, ErrorCode.FORBIDDEN);
        assertIdentity(send(get("/api/_jwt-authority"), oldAccess, 200), id, "user");
    }

    @ParameterizedTest
    @ValueSource(strings = {"inactive", "banned", "deleted", "missing"})
    void anExistingAccessTokenCannotAuthenticateAnUnavailableMember(String state) throws Exception {
        String id = member(UserRole.admin);
        String access = jwt.createAccessToken(id, "admin");
        makeUnavailable(id, state);
        assertError(get("/api/admin/users/_jwt-authority"), access, ErrorCode.UNAUTHORIZED);
    }

    @Test
    void aGuestNeedsNoMemberRowAndNeverReceivesMemberAuthority() throws Exception {
        String guest = "GUEST_" + UUID.randomUUID().toString().replace("-", "");
        String access = jwt.createAccessToken(guest, "guest");
        assertIdentity(send(get("/api/buildings/_jwt-authority"), access, 200), guest, "guest");
        assertError(get("/api/_jwt-authority"), access, ErrorCode.FORBIDDEN);
        assertError(get("/api/admin/users/_jwt-authority"), access, ErrorCode.FORBIDDEN);
        assertError(put("/api/complains/_jwt-authority"), access, ErrorCode.FORBIDDEN);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE user_id=?", Integer.class, guest)).isZero();
    }

    @Test
    void aSignedGuestRoleIsNotUpgradedWhenItsSubjectAlsoNamesAMember() throws Exception {
        String admin = member(UserRole.admin);
        String guestAccess = jwt.createAccessToken(admin, "guest");
        assertIdentity(send(get("/api/buildings/_jwt-authority"), guestAccess, 200), admin, "guest");
        assertError(get("/api/_jwt-authority"), guestAccess, ErrorCode.FORBIDDEN);
        assertError(get("/api/admin/users/_jwt-authority"), guestAccess, ErrorCode.FORBIDDEN);
    }

    @Test
    void aRequestWithoutATokenDoesNotInheritThePreviousRequestPrincipal() throws Exception {
        String id = member(UserRole.admin);
        send(get("/api/admin/users/_jwt-authority"), jwt.createAccessToken(id, "admin"), 200);
        assertError(get("/api/_jwt-authority"), null, ErrorCode.UNAUTHORIZED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"refresh", "oauth-signup", "oauth-link"})
    void nonAccessTokenTypesCannotAuthenticateProtectedRequests(String type) throws Exception {
        String id = member(UserRole.admin);
        String token = switch (type) {
            case "refresh" -> jwt.createRefreshToken(id);
            case "oauth-signup" -> jwt.createOauthSignupToken("test", "synthetic-provider", "fixture@example.test", "fixture");
            default -> jwt.createOauthLinkToken(id, "test");
        };
        assertError(get("/api/admin/users/_jwt-authority"), token, ErrorCode.TOKEN_TYPE_INVALID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-role", "blank-role", "missing-subject", "blank-subject"})
    void malformedSignedAccessClaimsCannotBorrowTheDatabaseRole(String malformed) throws Exception {
        String id = member(UserRole.admin);
        String subject = switch (malformed) {
            case "missing-subject" -> null;
            case "blank-subject" -> " ";
            default -> id;
        };
        String role = switch (malformed) {
            case "missing-role" -> null;
            case "blank-role" -> " ";
            default -> "admin";
        };
        assertError(get("/api/admin/users/_jwt-authority"), jwt.createAccessToken(subject, role), ErrorCode.TOKEN_INVALID);
    }

    @Test
    void anExpiredAccessTokenCannotBorrowTheDatabaseRole() throws Exception {
        String id = member(UserRole.admin);
        assertError(get("/api/admin/users/_jwt-authority"), jwt.createAccessToken(id, "admin", -60_000), ErrorCode.TOKEN_EXPIRED);
    }

    @Test
    void anAccessTokenSignedWithAnotherKeyCannotBorrowTheDatabaseRole() throws Exception {
        String id = member(UserRole.admin);
        String token = Jwts.builder().subject(id).claim("role", "admin").claim("typ", "access")
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor("different-synthetic-jwt-secret-at-least-32-bytes".getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
                .compact();
        assertError(get("/api/admin/users/_jwt-authority"), token, ErrorCode.TOKEN_INVALID);
    }

    @Test
    void refreshAfterDemotionRotatesTheStoredTokenAndIssuesTheCurrentRole() throws Exception {
        String id = member(UserRole.admin);
        String oldAccess = jwt.createAccessToken(id, "admin");
        String oldRefresh = storedRefresh(id);
        changeRole(id, UserRole.user, oldAccess);

        JsonNode data = refresh(oldRefresh, DEVICE, 200).path("data");
        String access = data.path("accessToken").asText();
        String rotated = data.path("refreshToken").asText();
        assertThat(data.path("deviceId").asText()).isEqualTo(DEVICE);
        assertThat(access).isNotBlank();
        assertThat(rotated).isNotBlank().isNotEqualTo(oldRefresh);
        jwt.validate(access);
        jwt.validate(rotated);
        assertThat(jwt.getRole(access)).isEqualTo("user");
        assertThat(jwt.getSubject(access)).isEqualTo(id);
        assertThat(jwt.getTokenType(rotated)).isEqualTo("refresh");
        assertThat(jdbc.queryForObject("SELECT revoked FROM refresh_tokens WHERE token_hash=?", Boolean.class, hash(oldRefresh))).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens WHERE user_id=? AND revoked=false", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT device_id FROM refresh_tokens WHERE token_hash=? AND revoked=false", String.class, hash(rotated))).isEqualTo(DEVICE);
        assertIdentity(send(get("/api/buildings/_jwt-authority"), access, 200), id, "user", List.of());
        assertError(get("/api/_jwt-authority"), access, ErrorCode.FORBIDDEN);
        assertError(get("/api/admin/users/_jwt-authority"), access, ErrorCode.FORBIDDEN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"inactive", "banned", "deleted"})
    void refreshRejectsUnavailableMembersWithoutRotatingTheirStoredToken(String state) throws Exception {
        String id = member(UserRole.admin);
        String token = storedRefresh(id);
        makeUnavailable(id, state);
        JsonNode response = refresh(token, DEVICE, 401);
        assertThat(response.path("errorCode").asText()).isEqualTo(ErrorCode.UNAUTHORIZED.getCode());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens WHERE user_id=?", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT revoked FROM refresh_tokens WHERE token_hash=?", Boolean.class, hash(token))).isFalse();
    }

    @Test
    void refreshKeepsTheExistingDeviceCheckAfterARoleChange() throws Exception {
        String id = member(UserRole.admin);
        String token = storedRefresh(id);
        changeRole(id, UserRole.user, jwt.createAccessToken(id, "admin"));
        assertThat(refresh(token, "another-synthetic-device", 401).path("errorCode").asText())
                .isEqualTo(ErrorCode.TOKEN_INVALID.getCode());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens WHERE user_id=?", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT revoked FROM refresh_tokens WHERE token_hash=?", Boolean.class, hash(token))).isFalse();
    }

    @Test
    void ownerApprovalAndRevocationAffectTheSameExistingConsumerToken() throws Exception {
        String id = member(UserRole.user);
        String access = jwt.createAccessToken(id, "user");
        assertError(get("/api/owner/_jwt-authority"), access, ErrorCode.FORBIDDEN);
        approveOwner(jdbc, id);
        assertIdentity(send(get("/api/owner/_jwt-authority"), access, 200), id, "user",
                List.of("ROLE_CUSTOMER", "ROLE_OWNER", "ROLE_USER"));
        jdbc.update("UPDATE account_role_grants SET grant_state='REVOKED' WHERE user_id=? AND role_code='OWNER'", id);
        assertError(get("/api/owner/_jwt-authority"), access, ErrorCode.FORBIDDEN);
        assertIdentity(send(get("/api/_jwt-authority"), access, 200), id, "user");
    }

    private String member(UserRole role) {
        String id = "jwt-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (user_id,user_nm,user_email,user_pwd,user_birth,user_tel,user_nickname,user_role,user_st,delete_yn,account_kind)
                VALUES (?, 'JWT fixture', ?, 'synthetic-test-hash', '2000-01-01', '010-0000-0000', ?, ?, 'active', 'N', ?)
                """, id, id + "@example.test", id, role.name(), role == UserRole.admin ? "STAFF" : "MEMBER");
        return id;
    }

    private void makeUnavailable(String id, String state) {
        switch (state) {
            case "missing" -> jdbc.update("DELETE FROM users WHERE user_id=?", id);
            case "deleted" -> jdbc.update("UPDATE users SET delete_yn='Y' WHERE user_id=?", id);
            default -> jdbc.update("UPDATE users SET user_st=? WHERE user_id=?", state, id);
        }
    }

    private void changeRole(String id, UserRole role, String adminAccess) throws Exception {
        JsonNode response = send(patch("/api/admin/users/" + id + "/role")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of("userRole", role.name()))), adminAccess, 200);
        assertThat(response.path("data").path("userRole").asText()).isEqualTo(role.name());
        assertThat(jdbc.queryForObject("SELECT user_role FROM users WHERE user_id=?", String.class, id)).isEqualTo(role.name());
    }

    private int activeContract(String id) {
        return new TransactionTemplate(transactions).execute(status -> {
            Building building = buildings.saveAndFlush(Building.builder().buildingNm("JWT authority fixture")
                    .buildingAddr("Synthetic fixture address").build());
            Room room = rooms.saveAndFlush(Room.builder().building(building).roomNo(1).floor(1)
                    .roomSize(new BigDecimal("20.00")).rentPrice(new BigDecimal("500000"))
                    .roomSt(RoomStatus.contracted).build());
            LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
            Contract contract = contractRows.saveAndFlush(Contract.builder().user(users.findById(id).orElseThrow())
                    .room(room).contractSt(ContractStatus.active).contractStart(today.minusMonths(1)).contractEnd(today.plusMonths(1))
                    .rentPrice(new BigDecimal("500000")).lessorNm("Synthetic lessor").lessorTel("010-1111-1111")
                    .lessorAddr("Synthetic fixture address").lessorRrn("test-only").build());
            residents.saveAndFlush(Resident.builder().buildingId(building.getBuildingId()).contractId(contract.getContractId())
                    .userId(id).build());
            return contract.getContractId();
        });
    }

    private String storedRefresh(String id) {
        String token = jwt.createRefreshToken(id);
        new TransactionTemplate(transactions).executeWithoutResult(status -> refreshTokens.saveAndFlush(RefreshToken.builder()
                .refreshTokenId(UUID.randomUUID().toString()).user(users.findById(id).orElseThrow()).tokenHash(hash(token))
                .deviceId(DEVICE).expiresAt(LocalDateTime.now().plusDays(1)).revoked(false).build()));
        return token;
    }

    private JsonNode refresh(String token, String device, int expectedStatus) throws Exception {
        return send(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("refreshToken", token, "deviceId", device))), null, expectedStatus);
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private JsonNode send(MockHttpServletRequestBuilder request, String token, int expectedStatus) throws Exception {
        request.contextPath("/api");
        if (token != null) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        MvcResult result = mvc.perform(request).andExpect(status().is(expectedStatus)).andReturn();
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private void assertError(MockHttpServletRequestBuilder request, String token, ErrorCode code) throws Exception {
        JsonNode response = send(request, token, code.getStatus().value());
        assertThat(response.path("success").asBoolean()).isFalse();
        assertThat(response.path("errorCode").asText()).isEqualTo(code.getCode());
    }

    private void assertIdentity(JsonNode response, String id, String role) {
        assertThat(response.path("userId").asText()).isEqualTo(id);
        assertThat(response.path("role").asText()).isEqualTo(role);
        List<String> expected = switch (role) {
            case "admin" -> List.of("ROLE_ADMIN");
            case "guest" -> List.of("ROLE_GUEST");
            case "tenant" -> List.of("ROLE_CUSTOMER", "ROLE_TENANT", "ROLE_USER");
            default -> List.of("ROLE_CUSTOMER", "ROLE_USER");
        };
        assertIdentity(response, id, role, expected);
    }

    private void assertIdentity(JsonNode response, String id, String role, List<String> authorities) {
        assertThat(response.path("userId").asText()).isEqualTo(id);
        assertThat(response.path("role").asText()).isEqualTo(role);
        assertThat(response.path("authorities")).isEqualTo(json.valueToTree(authorities));
        assertThat(response.path("principalAuthorities")).isEqualTo(response.path("authorities"));
    }

    /** Test-only routes keep the real authorization matcher/filter chain and expose no application data. */
    @RestController
    @Profile("jwt-authority-test")
    static class AuthorityProbe {
        @GetMapping({"/_jwt-authority", "/admin/users/_jwt-authority", "/owner/_jwt-authority", "/buildings/_jwt-authority"})
        Map<String, Object> getAuthority(Authentication authentication) {
            return identity(authentication);
        }

        @PutMapping("/complains/_jwt-authority")
        Map<String, Object> tenantAuthority(Authentication authentication) {
            return identity(authentication);
        }

        private Map<String, Object> identity(Authentication authentication) {
            AuthUser principal = (AuthUser) authentication.getPrincipal();
            return Map.of("userId", principal.getUserId(), "role", principal.getRole(),
                    "authorities", authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList(),
                    "principalAuthorities", principal.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList());
        }
    }
}
