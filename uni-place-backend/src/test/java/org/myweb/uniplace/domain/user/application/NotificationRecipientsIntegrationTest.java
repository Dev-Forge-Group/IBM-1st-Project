package org.myweb.uniplace.domain.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.myweb.uniplace.domain.notification.application.NotificationFactory;
import org.myweb.uniplace.domain.notification.application.NotificationServiceImpl;
import org.myweb.uniplace.domain.notification.domain.entity.Notification;
import org.myweb.uniplace.domain.notification.domain.enums.TargetType;
import org.myweb.uniplace.domain.notification.repository.NotificationRepository;
import org.myweb.uniplace.domain.user.domain.entity.User;
import org.myweb.uniplace.domain.user.domain.enums.UserRole;
import org.myweb.uniplace.domain.user.domain.enums.UserStatus;
import org.myweb.uniplace.domain.user.repository.UserRepository;
import org.myweb.uniplace.global.exception.BusinessException;
import org.myweb.uniplace.global.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real recipient eligibility and visibility of uncommitted caller state, without external delivery services. */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({NotificationServiceImpl.class, NotificationFactory.class, NotificationRecipientsAdapter.class})
@Testcontainers
class NotificationRecipientsIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("notification_recipients")
            .withUsername("notification_test")
            .withPassword("notification-test-only");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", MYSQL::getJdbcUrl);
        properties.add("spring.datasource.username", MYSQL::getUsername);
        properties.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired UserRepository users;
    @Autowired NotificationRepository notifications;
    @Autowired NotificationServiceImpl service;

    @Test
    void storedStatusAndAdministratorRolePreserveDeliveryToNonDeletedAccounts() {
        for (UserStatus status : UserStatus.values()) {
            user("recipient-" + status, UserRole.user, status, "N");
            user("admin-" + status, UserRole.admin, status, "N");
        }
        user("recipient-deleted", UserRole.user, UserStatus.active, "Y");
        user("admin-deleted", UserRole.admin, UserStatus.active, "y");

        for (String id : List.of("recipient-active", "recipient-inactive", "recipient-banned", "recipient-deleted")) {
            service.notifyUser(id, "BOUNDARY_USER", "User boundary", null, TargetType.security, null, "/mypage");
        }
        service.notifyAdmins("BOUNDARY_ADMIN", "Admin boundary", null, TargetType.security, null, "/admin");
        notifications.flush();

        assertThat(notifications.findAll().stream().filter(n -> "BOUNDARY_USER".equals(n.getCode())))
                .extracting(Notification::getReceiverId)
                .containsExactlyInAnyOrder("recipient-active", "recipient-inactive", "recipient-banned");
        assertThat(notifications.findAll().stream()
                .filter(n -> "BOUNDARY_ADMIN".equals(n.getCode())
                        && (n.getReceiverId().startsWith("admin-") || n.getReceiverId().startsWith("recipient-"))))
                .extracting(Notification::getReceiverId)
                .containsExactlyInAnyOrder("admin-active", "admin-inactive", "admin-banned");
        assertThatThrownBy(() -> service.notifyUser("missing-recipient", "BOUNDARY_USER", "Missing", null,
                TargetType.security, null, "/mypage"))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND));
    }

    @Test
    void callerRoleAndDeletionChangesAreReadWithoutCommittingAnIndependentTransaction() throws Exception {
        User deleted = user("pending-deletion", UserRole.user, UserStatus.active, "N");
        User promoted = user("pending-administrator", UserRole.user, UserStatus.inactive, "N");
        // Both changes remain managed and uncommitted in the caller's transaction.
        deleted.changeDeleteYN("Y");
        promoted.changeRole(UserRole.admin);

        service.notifyUser(deleted.getUserId(), "BOUNDARY_PENDING", "Pending deletion", null,
                TargetType.security, null, "/mypage");
        service.notifyAdmins("BOUNDARY_PENDING", "Pending promotion", null, TargetType.security, null, "/admin");
        notifications.flush();

        Set<String> fixtureIds = Set.of(deleted.getUserId(), promoted.getUserId());
        assertThat(notifications.findAll().stream()
                .filter(n -> "BOUNDARY_PENDING".equals(n.getCode()) && fixtureIds.contains(n.getReceiverId())))
                .extracting(Notification::getReceiverId).containsExactly(promoted.getUserId());

        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.prepareStatement("SELECT COUNT(*) FROM users WHERE user_id IN (?, ?)")) {
            statement.setString(1, deleted.getUserId());
            statement.setString(2, promoted.getUserId());
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).as("caller user writes remain uncommitted").isZero();
            }
            try (var notificationCount = connection.createStatement();
                 var rows = notificationCount.executeQuery("SELECT COUNT(*) FROM notification WHERE code = 'BOUNDARY_PENDING'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).as("notification writes share the caller transaction").isZero();
            }
        }
    }

    private User user(String id, UserRole role, UserStatus status, String deleted) {
        return users.saveAndFlush(User.builder().userId(id).userNm(id).userEmail(id + "@example.test")
                .userPwd("test-only-hash").userBirth(LocalDate.of(2000, 1, 1))
                .userTel("010" + Integer.toUnsignedString(id.hashCode()))
                .userNickname(id).userRole(role).userSt(status).deleteYN(deleted).firstSign("N").build());
    }
}
