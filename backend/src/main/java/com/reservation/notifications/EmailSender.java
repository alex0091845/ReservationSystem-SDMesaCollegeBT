package com.reservation.notifications;

/**
 * Delivers one email. Implementations are chosen by {@code EMAIL_PROVIDER}
 * (see {@link EmailSenderConfig}); adding a provider means adding one of these.
 */
public interface EmailSender {

    /** Stored with each sent notification, so a row shows how it went out. */
    String name();

    /** False means notices stay queued and nothing is claimed or sent. */
    default boolean enabled() {
        return true;
    }

    /** @throws EmailDeliveryException when the provider did not accept the message */
    void send(EmailMessage message);
}
