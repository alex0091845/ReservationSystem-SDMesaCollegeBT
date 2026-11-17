import { deleteEvent } from "../api.js";
import { validateReservationData } from "./reservationValidation.js";

export function validateEditedReservations({
    reservationsToSave,
    existingReservations,
    selectedReservation,
    users
}) {
    const selectedReservationId = String(selectedReservation?.id ?? "");
    const reservationsToCheck = existingReservations.filter(reservation => {
        return String(reservation.id) !== selectedReservationId;
    });

    for (const [index, reservationData] of reservationsToSave.entries()) {
        const validation = validateReservationData({
            reservationData,
            existingReservations: reservationsToCheck,
            users,
            requireId: index === 0
        });

        if (!validation.isValid) {
            return validation;
        }

        reservationsToCheck.push(reservationData);
    }

    return {
        isValid: true,
        message: ""
    };
}

export function getReservationSeries(reservation, reservationList) {
    const recurrenceGroupId = reservation?.recurrence_group_id;

    if (!recurrenceGroupId) {
        return [];
    }

    return reservationList.filter(candidate => {
        return candidate.recurrence_group_id === recurrenceGroupId;
    });
}

export async function deleteReservations(reservationsToDelete) {
    for (const reservation of reservationsToDelete) {
        await deleteEvent(reservation);
    }
}

export function bindBackdropClose(overlay, closeModal) {
    let pointerStartedOnBackdrop = false;

    overlay.addEventListener("pointerdown", event => {
        pointerStartedOnBackdrop = event.target === overlay;
    });

    overlay.addEventListener("click", event => {
        if (pointerStartedOnBackdrop && event.target === overlay) {
            closeModal();
        }

        pointerStartedOnBackdrop = false;
    });
}

// A cancelled reservation no longer holds its time slot, so it stays out of the calendar
// and the overlap checks. Only the host's and admins' reservation lists show it.
export function splitCancelledReservations(reservationList) {
    return {
        active: reservationList.filter(reservation => !reservation.cancelled_at),
        cancelled: reservationList.filter(reservation => reservation.cancelled_at)
    };
}

// Check-ins stay with a cancelled reservation, so attendees are left alone here
export function moveReservationToCancelled(reservation, cancelledAt, reservationList, cancelledList) {
    const index = reservationList.findIndex(candidate => String(candidate.id) === String(reservation.id));

    if (index !== -1) {
        reservationList.splice(index, 1);
    }

    cancelledList.push({ ...reservation, cancelled_at: cancelledAt });
}

export function getDeleteMenuLabel(isBusy, mode = "cancel") {
    if (!isBusy) {
        return "Cancel or Delete";
    }

    return mode === "series" ? "Deleting Series..." : "Cancelling...";
}

// The server removes a reservation that never happened and keeps anything else, so the
// prompt covers both outcomes
export function getCancelConfirmMessage(reservationTitle) {
    return `Cancel "${reservationTitle}"? Its time slot will open up for others. ` +
        "If it hasn't started and nobody has checked in, it is removed for good. " +
        "Otherwise it is kept with its check-ins.";
}

// Shown once the cancel goes through, since the reservation leaves the calendar
export function getCancelDoneMessage(reservationTitle, outcome) {
    return outcome === "deleted"
        ? `"${reservationTitle}" was cancelled and removed.`
        : `"${reservationTitle}" was cancelled.`;
}

// A cancelled reservation opens read-only: its details and check-ins stay visible, but
// nothing about it can be changed. Pass null for an editable reservation.
export function setReservationModalReadOnly(overlay, cancelledAt) {
    const isReadOnly = Boolean(cancelledAt);
    const title = overlay.querySelector(".modal-event-title");
    const subtitle = overlay.querySelector(".modal-event-department");

    [title, subtitle].forEach(element => {
        if (element.dataset.editText === undefined) {
            element.dataset.editText = element.textContent.trim();
        }
    });

    overlay.classList.toggle("reservation-read-only", isReadOnly);
    overlay.querySelector(".reservation-grid").inert = isReadOnly;
    title.textContent = isReadOnly ? "Reservation Details" : title.dataset.editText;
    subtitle.textContent = isReadOnly
        ? `${formatCancelledAt(cancelledAt)}.`
        : subtitle.dataset.editText;
}

function formatCancelledAt(cancelledAt) {
    const date = new Date(cancelledAt);

    if (Number.isNaN(date.getTime())) {
        return "Cancelled";
    }

    return `Cancelled ${date.toLocaleString([], {
        month: "short",
        day: "numeric",
        hour: "numeric",
        minute: "2-digit"
    })}`;
}

export function removeReservations(reservationsToRemove, reservationList, attendees) {
    const deletedReservationIds = new Set(
        reservationsToRemove.map(reservation => String(reservation.id))
    );

    const remainingReservations = reservationList.filter(reservation => {
        return !deletedReservationIds.has(String(reservation.id));
    });

    const remainingAttendees = attendees.filter(attendee => {
        return !deletedReservationIds.has(String(attendee.event_id));
    });

    reservationList.splice(0, reservationList.length, ...remainingReservations);
    attendees.splice(0, attendees.length, ...remainingAttendees);
}
