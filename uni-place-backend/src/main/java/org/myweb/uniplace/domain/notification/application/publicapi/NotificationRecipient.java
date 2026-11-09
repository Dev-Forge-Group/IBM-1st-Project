package org.myweb.uniplace.domain.notification.application.publicapi;

/** Minimal account information needed to deliver an internal notification. */
public record NotificationRecipient(String userId, boolean deleted) {
}
