package org.myweb.uniplace.domain.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.myweb.uniplace.domain.notification.application.publicapi.NotificationRecipient;
import org.myweb.uniplace.domain.notification.application.publicapi.NotificationRecipients;
import org.myweb.uniplace.domain.notification.domain.entity.Notification;
import org.myweb.uniplace.domain.notification.domain.enums.TargetType;
import org.myweb.uniplace.domain.notification.repository.NotificationRepository;
import org.myweb.uniplace.global.exception.BusinessException;
import org.myweb.uniplace.global.exception.ErrorCode;
import org.springframework.dao.DataAccessResourceFailureException;

@ExtendWith(MockitoExtension.class)
class NotificationRecipientsServiceTest {
    @Mock private NotificationRepository notificationRepository;
    @Mock private NotificationRecipients notificationRecipients;
    @Spy private NotificationFactory notificationFactory;
    @InjectMocks private NotificationServiceImpl notifications;

    @Test
    void missingRecipientRetainsTheUserNotFoundErrorWithoutSaving() {
        given(notificationRecipients.findById("missing")).willReturn(Optional.empty());

        assertThatThrownBy(() -> notify("missing"))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND));

        verifyNoInteractions(notificationRepository, notificationFactory);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Y", "y"})
    void aDeletedRecipientDoesNotReceiveAnEntry(String flag) {
        given(notificationRecipients.findById("deleted")).willReturn(Optional.of(recipient("deleted", flag)));

        notify("deleted");

        verifyNoInteractions(notificationRepository, notificationFactory);
    }

    @Test
    void anEligibleRecipientKeepsTheRequestedIdAndAllNotificationFields() {
        given(notificationRecipients.findById("Requested-ID")).willReturn(Optional.of(recipient("stored-id", "N")));

        notify("Requested-ID");

        ArgumentCaptor<Notification> entry = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(entry.capture());
        assertThat(entry.getValue().getReceiverId()).isEqualTo("Requested-ID");
        assertThat(entry.getValue().getCode()).isEqualTo("BOUNDARY_NOTICE");
        assertThat(entry.getValue().getMessage()).isEqualTo("Recipient boundary");
        assertThat(entry.getValue().getSenderId()).isNull();
        assertThat(entry.getValue().getTarget()).isEqualTo(TargetType.security);
        assertThat(entry.getValue().getTargetId()).isEqualTo(17);
        assertThat(entry.getValue().getUrlPath()).isEqualTo("/mypage/security");
        assertThat(entry.getValue().getIsRead()).isEqualTo("N");
    }

    @Test
    void aLegacyNullDeleteFlagDoesNotDisableDelivery() {
        given(notificationRecipients.findById("legacy")).willReturn(Optional.of(recipient("legacy", null)));

        notify("legacy");

        verify(notificationRepository).save(any(Notification.class));
    }

    @Test
    void administratorDeliverySkipsOnlyDeletedEntriesWithoutSortingOrDeduplicating() {
        given(notificationRecipients.findAdministrators()).willReturn(List.of(
                recipient("z-last-alphabetically", "N"), recipient("deleted", "y"),
                recipient("a-first-alphabetically", "N"), recipient("z-last-alphabetically", "N")));

        notifications.notifyAdmins("BOUNDARY_ADMIN", "Administrators", "sender", TargetType.security, null, "/admin");

        ArgumentCaptor<Notification> entries = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, org.mockito.Mockito.times(3)).save(entries.capture());
        assertThat(entries.getAllValues()).extracting(Notification::getReceiverId)
                .containsExactly("z-last-alphabetically", "a-first-alphabetically", "z-last-alphabetically");
        assertThat(entries.getAllValues()).allSatisfy(entry -> {
            assertThat(entry.getCode()).isEqualTo("BOUNDARY_ADMIN");
            assertThat(entry.getMessage()).isEqualTo("Administrators");
            assertThat(entry.getSenderId()).isEqualTo("sender");
            assertThat(entry.getTargetId()).isNull();
            assertThat(entry.getUrlPath()).isEqualTo("/admin");
        });
        var order = inOrder(notificationRepository);
        for (Notification entry : entries.getAllValues()) order.verify(notificationRepository).save(entry);
    }

    @Test
    void anEmptyAdministratorListIsANoOp() {
        given(notificationRecipients.findAdministrators()).willReturn(List.of());

        notifications.notifyAdmins("BOUNDARY_ADMIN", "Administrators", null, TargetType.security, null, "/admin");

        verifyNoInteractions(notificationRepository, notificationFactory);
    }

    @Test
    void recipientLookupFailuresPropagateWithoutSaving() {
        var failure = new DataAccessResourceFailureException("recipient lookup unavailable");
        given(notificationRecipients.findById("recipient")).willThrow(failure);

        assertThatThrownBy(() -> notify("recipient")).isSameAs(failure);

        verifyNoInteractions(notificationRepository, notificationFactory);
    }

    @Test
    void administratorLookupFailuresPropagateWithoutSaving() {
        var failure = new DataAccessResourceFailureException("administrator lookup unavailable");
        given(notificationRecipients.findAdministrators()).willThrow(failure);

        assertThatThrownBy(() -> notifications.notifyAdmins("BOUNDARY_ADMIN", "Administrators", null,
                TargetType.security, null, "/admin")).isSameAs(failure);

        verifyNoInteractions(notificationRepository, notificationFactory);
    }

    @Test
    void aNotificationSaveFailureIsNotSilentlyConvertedToSuccess() {
        given(notificationRecipients.findById("recipient")).willReturn(Optional.of(recipient("recipient", "N")));
        var failure = new DataAccessResourceFailureException("notification store unavailable");
        given(notificationRepository.save(any(Notification.class))).willThrow(failure);

        assertThatThrownBy(() -> notify("recipient")).isSameAs(failure);
    }

    private void notify(String receiver) {
        notifications.notifyUser(receiver, "BOUNDARY_NOTICE", "Recipient boundary", null,
                TargetType.security, 17, "/mypage/security");
    }

    private static NotificationRecipient recipient(String id, String deleted) {
        return new NotificationRecipient(id, "Y".equalsIgnoreCase(deleted));
    }
}
