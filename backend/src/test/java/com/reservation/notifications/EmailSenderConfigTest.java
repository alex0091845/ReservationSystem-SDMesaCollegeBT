package com.reservation.notifications;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class EmailSenderConfigTest {

    private final EmailSenderConfig config = new EmailSenderConfig();

    private EmailSender smtp(String from, String host, String username, String password, boolean startTls) {
        return config.emailSender("smtp", from, host, 587, username, password, startTls);
    }

    private void refused(String expected, Runnable action) {
        IllegalStateException error = assertThrows(IllegalStateException.class, action::run);
        assertTrue(error.getMessage().contains(expected), error.getMessage());
    }

    @Test
    void disabledSendsNothing() {
        EmailSender sender = config.emailSender(" Disabled ", "", "", 587, "", "", true);

        assertFalse(sender.enabled());
        assertThrows(IllegalStateException.class, () -> sender.send(new EmailMessage("a@b.edu", "s", "b")));
    }

    @Test
    void mockNeedsNoConfiguration() {
        EmailSender sender = config.emailSender("mock", "", "", 587, "", "", true);

        assertTrue(sender.enabled());
        assertEquals("mock", sender.name());
        assertDoesNotThrow(() -> sender.send(new EmailMessage("a@b.edu", "s", "b")));
    }

    @Test
    void smtpWithFullConfigurationStarts() {
        EmailSender sender = smtp("bt216@sdmesa.edu", "email-smtp.us-west-2.amazonaws.com", "user", "pass", true);

        assertEquals("smtp", sender.name());
    }

    @Test
    void unknownProviderStopsStartup() {
        refused("EMAIL_PROVIDER", () -> config.emailSender("sendmail", "", "", 587, "", "", true));
    }

    @Test
    void smtpNeedsAHost() {
        refused("EMAIL_SMTP_HOST", () -> smtp("bt216@sdmesa.edu", " ", "user", "pass", true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-an-address", "a@b.edu, c@d.edu", "BT-216 <bt216@sdmesa.edu>"})
    void smtpNeedsOneValidSender(String from) {
        refused("EMAIL_FROM", () -> smtp(from, "smtp.example.edu", "user", "pass", true));
    }

    @Test
    void usernameAndPasswordGoTogether() {
        refused("EMAIL_SMTP_PASSWORD", () -> smtp("bt216@sdmesa.edu", "smtp.example.edu", "user", "", true));
        refused("EMAIL_SMTP_USERNAME", () -> smtp("bt216@sdmesa.edu", "smtp.example.edu", "", "pass", true));
    }

    @Test
    void plainSmtpIsRefusedForARemoteServer() {
        refused("EMAIL_SMTP_STARTTLS", () -> smtp("bt216@sdmesa.edu", "smtp.example.edu", "", "", false));
    }

    @Test
    void configurationErrorsDoNotRepeatThePassword() {
        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> smtp("bt216@sdmesa.edu", "smtp.example.edu", "", "hunter2", true)
        );

        assertFalse(error.getMessage().contains("hunter2"));
    }
}
