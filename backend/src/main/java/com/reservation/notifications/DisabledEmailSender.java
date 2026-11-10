package com.reservation.notifications;

/** The default: notices stay queued in the database until a real provider is configured. */
final class DisabledEmailSender implements EmailSender {

    @Override
    public String name() {
        return "disabled";
    }

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public void send(EmailMessage message) {
        throw new IllegalStateException("Email sending is disabled.");
    }
}
