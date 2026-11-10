package com.reservation.notifications;

import jakarta.mail.SendFailedException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Sends through any SMTP server. Amazon SES is used through its SMTP endpoint, so
 * choosing SES or another relay is a configuration change, not a code change.
 */
final class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mailSender;
    private final String from;

    SmtpEmailSender(JavaMailSender mailSender, String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public String name() {
        return "smtp";
    }

    @Override
    public void send(EmailMessage message) {
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(from);
        mail.setTo(message.to());
        mail.setSubject(message.subject());
        mail.setText(message.body());

        try {
            mailSender.send(mail);
        } catch (MailSendException error) {
            throw new EmailDeliveryException(rejectedAddress(error));
        } catch (MailException error) {
            throw new EmailDeliveryException(false);
        }
    }

    // Jakarta Mail lists an address as invalid only on a permanent (5xx) refusal.
    // A temporary (4xx) refusal arrives as an unsent address and is worth retrying.
    private boolean rejectedAddress(MailSendException error) {
        for (Exception failure : error.getMessageExceptions()) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SendFailedException sendFailure &&
                    sendFailure.getInvalidAddresses() != null &&
                    sendFailure.getInvalidAddresses().length > 0) {
                    return true;
                }
            }
        }

        return false;
    }
}
