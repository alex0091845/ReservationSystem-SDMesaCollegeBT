package com.reservation.notifications;

/** One plain-text email to exactly one recipient, so hosts never see each other's addresses. */
public record EmailMessage(String to, String subject, String body) {
}
