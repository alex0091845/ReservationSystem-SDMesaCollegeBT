package com.reservation.notifications;

/**
 * The provider did not accept a message. Carries no provider text, because that
 * can include recipient addresses.
 */
public class EmailDeliveryException extends RuntimeException {

    private final boolean permanent;

    public EmailDeliveryException(boolean permanent) {
        super(permanent ? "The provider rejected the message." : "The provider could not be reached.");
        this.permanent = permanent;
    }

    /** True when retrying the same message cannot succeed, such as a rejected address. */
    public boolean permanent() {
        return permanent;
    }
}
