package com.reservation.notifications;

/**
 * For local development: runs the whole flow and marks notices sent, but no email
 * leaves the machine. Rows it handles are stored with {@code sent_via = 'mock'}.
 */
final class MockEmailSender implements EmailSender {

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public void send(EmailMessage message) {
        // Nothing to do.
    }
}
