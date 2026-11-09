package org.myweb.uniplace.domain.notification.application.publicapi;

import java.util.List;
import java.util.Optional;

/** Recipient lookup port; account login status does not determine notification eligibility. */
public interface NotificationRecipients {
    Optional<NotificationRecipient> findById(String userId);

    /** Returns administrator entries in lookup order, including deleted accounts. */
    List<NotificationRecipient> findAdministrators();
}
