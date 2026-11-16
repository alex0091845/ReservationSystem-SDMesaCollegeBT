import { deleteEvent, deleteEventSeries, getReservationDateTimeParts } from "../api.js";
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

export async function deleteReservationSeries(reservationsToDelete, allReservations) {
    const recurrenceGroupId = reservationsToDelete[0]?.recurrence_group_id;
    const isWholeSeries =
        Boolean(recurrenceGroupId) &&
        reservationsToDelete.every(reservation => {
            return reservation.recurrence_group_id === recurrenceGroupId;
        }) &&
        reservationsToDelete.length ===
            getReservationSeries(reservationsToDelete[0], allReservations).length;

    if (isWholeSeries) {
        await deleteEventSeries(recurrenceGroupId);
        return;
    }

    await deleteReservations(reservationsToDelete);
}

export function canSaveWholeSeries(seriesReservations, canEditReservation) {
    return seriesReservations.length >= 2 &&
        seriesReservations.every(canEditReservation);
}

function getReservationLocalDate(value) {
    const parts = getReservationDateTimeParts(new Date(value));

    return [
        String(parts.year).padStart(4, "0"),
        String(parts.month).padStart(2, "0"),
        String(parts.day).padStart(2, "0")
    ].join("-");
}

function getReservationLocalTime(value) {
    const parts = getReservationDateTimeParts(new Date(value));

    return [
        String(parts.hour).padStart(2, "0"),
        String(parts.minute).padStart(2, "0")
    ].join(":");
}

// Turns the edit form into a whole-series update. Returns either an errorMessage to show
// or the data to send, plus the question to ask before sending it. Times are only sent
// when the form's time of day differs from the open reservation's.
export function prepareSeriesUpdate({
    selectedReservation,
    seriesSize,
    timeRanges,
    details
}) {
    if (timeRanges.length > 1) {
        return {
            errorMessage: "Remove the extra time blocks to save the whole series."
        };
    }

    const [timeRange] = timeRanges;

    if (
        getReservationLocalDate(timeRange.start) !==
        getReservationLocalDate(selectedReservation.start_time)
    ) {
        return {
            errorMessage: "A date change applies to one reservation only. Use Save for that."
        };
    }

    const seriesData = {
        title: details.title,
        description: details.description,
        event_type: details.event_type,
        department: details.department,
        is_public: details.is_public
    };
    const localStartTime = getReservationLocalTime(timeRange.start);
    const localEndTime = getReservationLocalTime(timeRange.end);
    const timesChanged =
        localStartTime !== getReservationLocalTime(selectedReservation.start_time) ||
        localEndTime !== getReservationLocalTime(selectedReservation.end_time);

    if (timesChanged) {
        seriesData.local_start_time = localStartTime;
        seriesData.local_end_time = localEndTime;
    }

    const reservationTitle = selectedReservation.title || "this recurring reservation";

    return {
        seriesData,
        confirmMessage:
            `Apply these changes to all ${seriesSize} reservations in "${reservationTitle}"?` +
            (timesChanged ? " Reservations that have already started keep their time." : "")
    };
}

// Swaps the updated reservations into the list by id. The update response carries no host
// details, so each entry keeps the host_user it already had.
export function replaceUpdatedReservations(reservationList, updatedReservations) {
    const updatedById = new Map(
        updatedReservations.map(reservation => [String(reservation.id), reservation])
    );

    return reservationList.map(reservation => {
        const updatedReservation = updatedById.get(String(reservation.id));

        if (!updatedReservation) {
            return reservation;
        }

        return {
            ...reservation,
            ...updatedReservation,
            host_user: updatedReservation.host_user || reservation.host_user
        };
    });
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
