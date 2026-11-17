import { deleteEvent } from "../api.js";
import { validateReservationData } from "./reservationValidation.js";
import { getAttendeesForEvent } from "../ui/attendees.js";

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

    if (mode === "series") {
        return "Deleting Series...";
    }

    return mode === "delete" ? "Deleting..." : "Cancelling...";
}

// The server deletes a reservation that hasn't started and has no check-ins, and cancels
// anything else, so the menu and prompt say which one is going to happen
export function willCancelDelete(reservation, attendees) {
    return new Date(reservation.start_time) > new Date() &&
        getAttendeesForEvent(reservation, attendees).length === 0;
}

export function getCancelOptionLabel(reservation, attendees) {
    return willCancelDelete(reservation, attendees) ? "Delete Reservation" : "Cancel Reservation";
}

export function getCancelConfirmMessage(reservationTitle, willDelete) {
    return willDelete
        ? `Delete "${reservationTitle}"? This can't be undone.`
        : `Cancel "${reservationTitle}"? Its time slot will open up for others.`;
}

// Pops up once the server answers, since the reservation leaves the calendar
export function openCancelResultModal(overlay, reservationTitle, outcome) {
    const wasDeleted = outcome === "deleted";

    overlay.querySelector(".modal-event-title").textContent =
        wasDeleted ? "Reservation Deleted" : "Reservation Cancelled";
    overlay.querySelector(".modal-value").textContent =
        `"${reservationTitle}" was ${wasDeleted ? "deleted" : "cancelled"}.`;
    overlay.classList.add("active");
    overlay.setAttribute("aria-hidden", "false");
    overlay.querySelector(".modal-close-btn").focus();
}

export function bindCancelResultModal(overlay) {
    const close = () => {
        overlay.classList.remove("active");
        overlay.setAttribute("aria-hidden", "true");
    };

    overlay.querySelector(".modal-close-btn").addEventListener("click", close);
    bindBackdropClose(overlay, close);
    document.addEventListener("keydown", event => {
        if (event.key === "Escape" && overlay.classList.contains("active")) {
            close();
        }
    });
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
