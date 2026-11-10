package com.reservation.notifications;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * Picks the email provider from {@code EMAIL_PROVIDER}. A bad configuration stops the
 * app at startup, so it cannot look healthy while quietly never sending.
 */
@Configuration
@EnableScheduling
public class EmailSenderConfig {

    private static final String TIMEOUT_MILLIS = "10000";
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "::1");

    @Bean
    EmailSender emailSender(
        @Value("${notifications.email.provider}") String provider,
        @Value("${notifications.email.from}") String from,
        @Value("${notifications.email.smtp.host}") String host,
        @Value("${notifications.email.smtp.port}") int port,
        @Value("${notifications.email.smtp.username}") String username,
        @Value("${notifications.email.smtp.password}") String password,
        @Value("${notifications.email.smtp.starttls}") boolean startTls
    ) {
        return switch (provider.trim().toLowerCase(Locale.ROOT)) {
            case "disabled" -> new DisabledEmailSender();
            case "mock" -> new MockEmailSender();
            case "smtp" -> smtp(from.trim(), host.trim(), port, username.trim(), password, startTls);
            default -> throw new IllegalStateException("EMAIL_PROVIDER must be disabled, mock, or smtp.");
        };
    }

    private EmailSender smtp(
        String from, String host, int port, String username, String password, boolean startTls
    ) {
        if (host.isEmpty()) {
            throw new IllegalStateException("EMAIL_SMTP_HOST is required when EMAIL_PROVIDER is smtp.");
        }

        if (!validAddress(from)) {
            throw new IllegalStateException("EMAIL_FROM must be one email address when EMAIL_PROVIDER is smtp.");
        }

        if (username.isEmpty() != password.isEmpty()) {
            throw new IllegalStateException("EMAIL_SMTP_USERNAME and EMAIL_SMTP_PASSWORD must be set together.");
        }

        // Plain SMTP would expose the password and reservation details on the network.
        if (!startTls && !LOOPBACK_HOSTS.contains(host)) {
            throw new IllegalStateException("EMAIL_SMTP_STARTTLS can only be false for a server on this machine.");
        }

        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost(host);
        mailSender.setPort(port);
        mailSender.setDefaultEncoding(StandardCharsets.UTF_8.name());

        if (!username.isEmpty()) {
            mailSender.setUsername(username);
            mailSender.setPassword(password);
        }

        Properties properties = mailSender.getJavaMailProperties();
        properties.put("mail.smtp.auth", String.valueOf(!username.isEmpty()));
        properties.put("mail.smtp.starttls.enable", String.valueOf(startTls));
        properties.put("mail.smtp.starttls.required", String.valueOf(startTls));
        properties.put("mail.smtp.connectiontimeout", TIMEOUT_MILLIS);
        properties.put("mail.smtp.timeout", TIMEOUT_MILLIS);
        properties.put("mail.smtp.writetimeout", TIMEOUT_MILLIS);

        return new SmtpEmailSender(mailSender, from);
    }

    /** True for one bare address, with no display name, list, or line break. */
    static boolean validAddress(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }

        try {
            InternetAddress address = new InternetAddress(value, true);
            address.validate();

            return value.equals(address.getAddress());
        } catch (AddressException error) {
            return false;
        }
    }
}
