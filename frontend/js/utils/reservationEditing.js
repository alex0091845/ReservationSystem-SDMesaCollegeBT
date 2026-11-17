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
// or the data to send. Times are only sent when the form's time of day differs from the
// open reservation's.
export function prepareSeriesUpdate({
    selectedReservation,
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
            errorMessage: "A date change applies to one reservation only. Choose \"Only This Reservation\" for that."
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

    return {
        seriesData
    };
}

// Asks whether a save applies to the open reservation or the whole series. Resolves to
// "single", "series" or null when the user cancels.
export function chooseSeriesSaveScope({ seriesSize, title, returnFocusElement }) {
    return new Promise(resolve => {
        const overlay = document.createElement("div");
        const dialog = document.createElement("div");
        const heading = document.createElement("h3");
        const message = document.createElement("p");
        const hint = document.createElement("p");
        const actions = document.createElement("div");
        const singleButton = document.createElement("button");
        const seriesButton = document.createElement("button");
        const cancelButton = document.createElement("button");
        const buttons = [singleButton, seriesButton, cancelButton];

        overlay.className = "draft-exit-prompt";
        dialog.className = "draft-exit-card";
        actions.className = "draft-exit-actions";
        dialog.id = "seriesSaveScopeDialog";
        heading.id = "seriesSaveScopeTitle";
        dialog.setAttribute("role", "dialog");
        dialog.setAttribute("aria-modal", "true");
        dialog.setAttribute("aria-labelledby", heading.id);
        message.id = "seriesSaveScopeMessage";
        hint.id = "seriesSaveScopeHint";
        dialog.setAttribute("aria-describedby", `${message.id} ${hint.id}`);
        heading.textContent = "Save Recurring Reservation";
        message.textContent =
            `Apply your changes to only this reservation or to all ${seriesSize} in ` +
            `"${title || "this recurring reservation"}"?`;
        hint.className = "draft-exit-hint";
        hint.textContent =
            "If you change the time, reservations that have already started keep theirs.";

        singleButton.id = "seriesSaveScopeSingleBtn";
        seriesButton.id = "seriesSaveScopeSeriesBtn";
        cancelButton.id = "seriesSaveScopeCancelBtn";
        buttons.forEach(button => {
            button.type = "button";
        });
        singleButton.className = "reservation-primary-btn";
        seriesButton.className = "reservation-secondary-btn";
        cancelButton.className = "reservation-secondary-btn";
        singleButton.textContent = "Only This Reservation";
        seriesButton.textContent = "All Reservations in the Series";
        cancelButton.textContent = "Cancel";

        function handleKeydown(event) {
            if (event.key === "Escape") {
                event.preventDefault();
                event.stopPropagation();
                closeChoice(null);
                return;
            }

            if (event.key !== "Tab") {
                return;
            }

            const first = buttons[0];
            const last = buttons[buttons.length - 1];

            if (!dialog.contains(document.activeElement)) {
                event.preventDefault();
                first.focus();
            } else if (event.shiftKey && document.activeElement === first) {
                event.preventDefault();
                last.focus();
            } else if (!event.shiftKey && document.activeElement === last) {
                event.preventDefault();
                first.focus();
            }
        }

        function closeChoice(choice) {
            document.removeEventListener("keydown", handleKeydown, true);
            overlay.remove();

            if (returnFocusElement?.isConnected && !returnFocusElement.disabled) {
                returnFocusElement.focus();
            }

            resolve(choice);
        }

        // A held Enter key or a double tap on Save Changes would otherwise land on a
        // button the moment the dialog appears, before the user has seen it.
        const openedAt = Date.now();

        function choose(choice) {
            if (Date.now() - openedAt < 300) {
                return;
            }

            closeChoice(choice);
        }

        singleButton.addEventListener("click", () => choose("single"));
        seriesButton.addEventListener("click", () => choose("series"));
        cancelButton.addEventListener("click", () => choose(null));
        bindBackdropClose(overlay, () => closeChoice(null));
        document.addEventListener("keydown", handleKeydown, true);

        actions.append(...buttons);
        dialog.append(heading, message, hint, actions);
        overlay.appendChild(dialog);
        document.body.appendChild(overlay);
        singleButton.focus();
    });
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
