package org.myweb.uniplace.domain.user.application;

import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.myweb.uniplace.domain.notification.application.publicapi.NotificationRecipient;
import org.myweb.uniplace.domain.notification.application.publicapi.NotificationRecipients;
import org.myweb.uniplace.domain.user.domain.entity.User;
import org.myweb.uniplace.domain.user.domain.enums.UserRole;
import org.myweb.uniplace.domain.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationRecipientsAdapter implements NotificationRecipients {
    private final UserRepository userRepository;

    @Override
    public Optional<NotificationRecipient> findById(String userId) {
        return userRepository.findById(userId).map(NotificationRecipientsAdapter::recipient);
    }

    @Override
    public List<NotificationRecipient> findAdministrators() {
        return userRepository.findAllByUserRole(UserRole.admin).stream()
                .map(NotificationRecipientsAdapter::recipient)
                .toList();
    }

    private static NotificationRecipient recipient(User user) {
        return new NotificationRecipient(user.getUserId(), "Y".equalsIgnoreCase(user.getDeleteYN()));
    }
}
