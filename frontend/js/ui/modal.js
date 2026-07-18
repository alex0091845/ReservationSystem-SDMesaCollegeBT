import { createAttendee, createEvent, getEventTypes, getEvents, isUserDisabled } from "../api.js";
import { formatReadableDate } from "../utils/dateUtils.js";
import { validateReservationData } from "../utils/reservationValidation.js";
import { renderEventTypeOptions } from "./eventTypeOptions.js";
import {
    applyReservationFormDraft,
    collectReservationFormDraft,
    createReservationDraftAutosave
} from "./reservationDrafts.js";
import { createReservationTimePicker } from "./reservationTimePicker.js";

const reservationModalOverlay = document.getElementById("reservationModalOverlay");
const openReservationModalBtn = document.getElementById("openReservationModalBtn");
const reservationModalCloseBtn = document.getElementById("reservationModalCloseBtn");
const reservationCancelBtn = document.getElementById("reservationCancelBtn");
const reservationForm = document.getElementById("reservationForm");
const reservationSubmitBtn = reservationForm?.querySelector('button[type="submit"]');
const reservationStatus = document.getElementById("reservationStatus");
const reservationTypeSelect = document.getElementById("reservationType");
const reservationStartInput = document.getElementById("reservationStart");
const reservationEndInput = document.getElementById("reservationEnd");
const reservationRecurringInput = document.getElementById("reservationRecurring");
const reservationRecurringControls = document.getElementById("reservationRecurringControls");
const reservationRecurringDuration = document.getElementById("reservationRecurringDuration");
const currentHostUserId = sessionStorage.getItem("currentUserId") || 1;
const OVERLAP_VALIDATION_MESSAGE_PREFIX = "This reservation overlaps with";
const reservationTimePicker = createReservationTimePicker({
    container: document.getElementById("reservationTimePicker"),
    summaryElement: document.getElementById("reservationTimeSummary"),
    startInput: reservationStartInput,
    endInput: reservationEndInput
});
const reservationDraftAutosave = createReservationDraftAutosave({
    form: reservationForm,
    timePicker: reservationTimePicker,
    draftType: "create",
    getHostUserId: () => currentHostUserId,
    collectPayload: () => collectReservationFormDraft(
        reservationForm,
        reservationTimePicker
    ),
    applyPayload: payload => {
        applyReservationFormDraft(
            reservationForm,
            reservationTimePicker,
            payload
        );
        renderRecurringControls();
    }
});
let reservationEventTypes = [];
let reservationPointerStartedOnBackdrop = false;
let eventModalReturnFocusElement = null;
let reservationModalReturnFocusElement = null;
let selectedCheckInEvent = null;

export function createModalController(elements) {
    const {
        eventModalOverlay,
        modalEventTitle,
        modalEventDepartment,
        modalHost,
        modalDate,
        modalTime,
        modalDescription,
        modalEventType,
        modalIsPublic,
        eventCheckInSection,
        eventCheckInForm,
        eventCheckInSubmitBtn,
        eventCheckInStatus
    } = elements;

    bindEventCheckInForm();

    // Opens event details modal
    async function openEventModal(eventData) {
        eventModalReturnFocusElement = getModalReturnFocusElement(
            eventModalOverlay
        );
        selectedCheckInEvent = eventData;

        const startDate = new Date(eventData.start_time);
        const endDate = new Date(eventData.end_time);
        const hostUser = await getEventHostUser(eventData);

        modalEventTitle.textContent = eventData.title;
        modalEventDepartment.textContent = eventData.department;
        modalHost.textContent = getUserFullName(hostUser);

        modalDate.textContent = formatReadableDate(startDate);

        modalTime.textContent = `
            ${startDate.toLocaleTimeString([], {
            hour: "numeric",
            minute: "2-digit"
        })}
            -
            ${endDate.toLocaleTimeString([], {
            hour: "numeric",
            minute: "2-digit"
        })}
        `;

        modalDescription.textContent = eventData.description;
        modalEventType.textContent = eventData.event_type;

        modalIsPublic.textContent =
            eventData.is_public ? "Public" : "Private";
        renderEventCheckInForm();

        eventModalOverlay.classList.add("active");
        eventModalOverlay.setAttribute("aria-hidden", "false");
    }

    // Closes event details modal
    function closeEventModal() {
        restoreFocusBeforeHide(
            eventModalOverlay,
            eventModalReturnFocusElement
        );

        eventModalOverlay.classList.remove("active");
        eventModalOverlay.setAttribute("aria-hidden", "true");

        eventModalReturnFocusElement = null;
        selectedCheckInEvent = null;
        resetEventCheckInForm();
    }

    return {
        openEventModal,
        closeEventModal
    };

    function bindEventCheckInForm() {
        if (!eventCheckInForm) {
            return;
        }

        eventCheckInForm.addEventListener(
            "submit",
            handleEventCheckInSubmit
        );
    }

    function renderEventCheckInForm() {
        if (!eventCheckInSection) {
            return;
        }

        const isFacultyLoggedIn =
            sessionStorage.getItem("facultyLoggedIn") === "true";

        eventCheckInSection.hidden = isFacultyLoggedIn;

        if (isFacultyLoggedIn) {
            resetEventCheckInForm();
            return;
        }

        setCheckInStatus("");
        setCheckInSubmitting(false);
    }

    async function handleEventCheckInSubmit(event) {
        event.preventDefault();

        if (!selectedCheckInEvent?.id) {
            setCheckInStatus(
                "Could not find this event. Please close and reopen the event details.",
                "error"
            );
            return;
        }

        const formData = new FormData(eventCheckInForm);
        const sdccdIdValue = formData.get("sdccd_id")?.trim();

        const attendeeData = {
            event_id: selectedCheckInEvent.id,
            sdccd_id: sdccdIdValue ? Number(sdccdIdValue) : null,
            first_name: formData.get("first_name").trim(),
            last_name: formData.get("last_name").trim(),
            email: formData.get("email").trim()
        };

        setCheckInSubmitting(true);
        setCheckInStatus("");

        try {
            await createAttendee(attendeeData);

            eventCheckInForm.reset();
            setCheckInStatus(
                "You're checked in. Thank you!",
                "success"
            );
        } catch (error) {
            console.error("Could not check in attendee:", error);
            setCheckInStatus(
                "Could not complete check-in. Please try again.",
                "error"
            );
        } finally {
            setCheckInSubmitting(false);
        }
    }

    function resetEventCheckInForm() {
        if (eventCheckInForm) {
            eventCheckInForm.reset();
        }

        setCheckInStatus("");
        setCheckInSubmitting(false);
    }

    function setCheckInSubmitting(isSubmitting) {
        if (!eventCheckInSubmitBtn) {
            return;
        }

        eventCheckInSubmitBtn.disabled = isSubmitting;
        eventCheckInSubmitBtn.textContent = isSubmitting
            ? "Checking In..."
            : "Check In";
    }

    function setCheckInStatus(message, statusType = "") {
        if (!eventCheckInStatus) {
            return;
        }

        eventCheckInStatus.textContent = message;
        eventCheckInStatus.classList.toggle(
            "success",
            statusType === "success"
        );
        eventCheckInStatus.classList.toggle(
            "error",
            statusType === "error"
        );
    }
}

async function getEventHostUser(eventData) {
    return eventData.host_user || eventData.users || null;
}

function getUserFullName(user) {
    if (!user) {
        return "Unknown host";
    }

    return `${user.first_name || ""} ${user.last_name || ""}`.trim() || user.email || "Unknown host";
}

// Opens reservation creation modal
async function openReservationModal() {
    await loadReservationEventTypes();

    if (await isUserDisabled(currentHostUserId)) {
        return;
    }

    reservationModalReturnFocusElement = getModalReturnFocusElement(
        reservationModalOverlay
    );

    reservationModalOverlay.classList.add("active");
    reservationModalOverlay.setAttribute("aria-hidden", "false");
    reservationTimePicker.setWeekFromDate(new Date());
    await reservationDraftAutosave.activate();
}

async function loadReservationEventTypes(selectedValue = reservationTypeSelect?.value || "") {
    reservationEventTypes = await getEventTypes();

    renderEventTypeOptions(
        reservationTypeSelect,
        reservationEventTypes,
        { selectedValue }
    );
}

// Closes reservation creation modal
function closeReservationModal({ flushDraft = true } = {}) {
    if (flushDraft) {
        reservationDraftAutosave.deactivate({ flush: true });
    } else {
        reservationDraftAutosave.deactivate();
    }

    restoreFocusBeforeHide(
        reservationModalOverlay,
        reservationModalReturnFocusElement
    );

    reservationModalOverlay.classList.remove("active");
    reservationModalOverlay.setAttribute("aria-hidden", "true");

    reservationForm.reset();
    reservationTimePicker.clear();
    renderRecurringControls();
    setReservationStatus("");
    setReservationSubmitting(false);
    reservationModalReturnFocusElement = null;
}

function setReservationStatus(message, statusType = "") {
    if (!reservationStatus) {
        return;
    }

    reservationStatus.textContent = message;
    reservationStatus.classList.toggle("error", statusType === "error");
    reservationStatus.classList.toggle("success", statusType === "success");
}

function setReservationSubmitting(isSubmitting) {
    if (!reservationSubmitBtn) {
        return;
    }

    reservationSubmitBtn.disabled = isSubmitting;
    reservationSubmitBtn.textContent = isSubmitting
        ? "Creating..."
        : "Create Reservation";
}

function renderRecurringControls() {
    if (!reservationRecurringInput || !reservationRecurringControls) {
        return;
    }

    const isRecurring = reservationRecurringInput.checked;

    reservationRecurringControls.classList.toggle("disabled", !isRecurring);

    if (reservationRecurringDuration) {
        reservationRecurringDuration.disabled = !isRecurring;
        reservationRecurringDuration.required = isRecurring;
    }
}

function getModalReturnFocusElement(overlay) {
    const activeElement = document.activeElement;

    if (!activeElement || overlay.contains(activeElement)) {
        return null;
    }

    return activeElement;
}

function restoreFocusBeforeHide(overlay, returnFocusElement) {
    const activeElement = document.activeElement;

    if (!activeElement || !overlay.contains(activeElement)) {
        return;
    }

    if (
        returnFocusElement &&
        returnFocusElement.isConnected &&
        typeof returnFocusElement.focus === "function"
    ) {
        returnFocusElement.focus();
        return;
    }

    activeElement.blur();
}

// Open modal button
if (openReservationModalBtn) {
    openReservationModalBtn.addEventListener(
        "click",
        openReservationModal
    );
}

if (reservationRecurringInput) {
    reservationRecurringInput.addEventListener("change", renderRecurringControls);
    renderRecurringControls();
}

// Close modal buttons
reservationModalCloseBtn.addEventListener(
    "click",
    closeReservationModal
);

reservationCancelBtn.addEventListener(
    "click",
    closeReservationModal
);

// Close modal when clicking overlay
reservationModalOverlay.addEventListener("pointerdown", event => {
    reservationPointerStartedOnBackdrop = event.target === reservationModalOverlay;
});

reservationModalOverlay.addEventListener("click", (event) => {
    if (
        reservationPointerStartedOnBackdrop &&
        event.target === reservationModalOverlay
    ) {
        closeReservationModal();
    }

    reservationPointerStartedOnBackdrop = false;
});

// Close modal with escape key
document.addEventListener("keydown", (event) => {
    const modalIsOpen =
        reservationModalOverlay.classList.contains("active");

    if (event.key === "Escape" && modalIsOpen) {
        closeReservationModal();
    }
});

// Submit reservation form
reservationForm.addEventListener("submit", async (event) => {
    event.preventDefault();

    if (!reservationForm.reportValidity()) {
        return;
    }

    const formData = new FormData(reservationForm);

    const selectedTimeRanges = reservationTimePicker.getRanges();

    if (selectedTimeRanges.length === 0) {
        setReservationStatus("Select at least one reservation time block.", "error");
        return;
    }

    const primaryRange = selectedTimeRanges[0];

    const eventData = {
        host_user_id: currentHostUserId,
        start_time: primaryRange.start.toISOString(),
        end_time: primaryRange.end.toISOString(),
        event_type: formData.get("type"),
        description: formData.get("description"),
        title: formData.get("title"),
        department: formData.get("department"),
        is_public: formData.get("access") === "open"
    };

    setReservationStatus("");
    setReservationSubmitting(true);

    try {
        const eventOccurrences = buildReservationOccurrences(
            eventData,
            selectedTimeRanges
        );
        const existingReservations = await getEvents();
        const reservationPlan = buildCreatableReservationPlan(
            eventOccurrences,
            existingReservations
        );

        if (!reservationPlan.isValid) {
            setReservationStatus(reservationPlan.message, "error");
            return;
        }

        if (
            reservationPlan.skippedOccurrences.length > 0 &&
            !confirmSkippedRecurringReservations(reservationPlan.skippedOccurrences)
        ) {
            return;
        }

        const createdEvents = [];

        for (const occurrence of reservationPlan.creatableOccurrences) {
            createdEvents.push(await createEvent(occurrence));
        }

        await reservationDraftAutosave.discard();
        closeReservationModal({ flushDraft: false });

        window.dispatchEvent(new CustomEvent("reservation:created", {
            detail: {
                reservations: createdEvents.length > 0
                    ? createdEvents
                    : reservationPlan.creatableOccurrences,
                skippedReservations: reservationPlan.skippedOccurrences
            }
        }));
    } catch (error) {
        console.error("Error creating reservation:", error);
        setReservationStatus(
            error.message || "Could not create reservation. Please try again.",
            "error"
        );
    } finally {
        setReservationSubmitting(false);
    }
});

function buildReservationOccurrences(eventData, selectedTimeRanges = []) {
    const durationWeeks = getReservationDurationWeeks();
    const baseRanges = selectedTimeRanges.length > 0
        ? selectedTimeRanges
        : [{
            start: new Date(eventData.start_time),
            end: new Date(eventData.end_time)
        }];
    const recurrenceGroupId =
        durationWeeks > 1
            ? createRecurrenceGroupId()
            : null;

    return baseRanges.flatMap(timeRange => {
        const baseEventData = {
            ...eventData,
            start_time: timeRange.start.toISOString(),
            end_time: timeRange.end.toISOString()
        };

        return Array.from({ length: durationWeeks }, (_, index) => {
            return {
                ...offsetReservationByWeeks(baseEventData, index),
                ...(recurrenceGroupId ? { recurrence_group_id: recurrenceGroupId } : {})
            };
        });
    });
}

function getReservationDurationWeeks() {
    if (!reservationRecurringInput?.checked) {
        return 1;
    }

    const durationWeeks = Number(reservationRecurringDuration?.value || 1);

    if (!Number.isFinite(durationWeeks)) {
        return 1;
    }

    return Math.max(1, Math.floor(durationWeeks));
}

function offsetReservationByWeeks(eventData, weekOffset) {
    if (weekOffset === 0) {
        return eventData;
    }

    return {
        ...eventData,
        start_time: offsetIsoDateByDays(eventData.start_time, weekOffset * 7),
        end_time: offsetIsoDateByDays(eventData.end_time, weekOffset * 7)
    };
}

function offsetIsoDateByDays(value, dayOffset) {
    const date = new Date(value);

    date.setDate(date.getDate() + dayOffset);

    return date.toISOString();
}

function createRecurrenceGroupId() {
    if (window.crypto?.randomUUID) {
        return window.crypto.randomUUID();
    }

    return `recurrence-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
}

function buildCreatableReservationPlan(eventOccurrences, existingReservations) {
    const reservationsToCheck = [...existingReservations];
    const creatableOccurrences = [];
    const skippedOccurrences = [];
    const canSkipOverlaps =
        getReservationDurationWeeks() > 1 &&
        eventOccurrences.length > 1;

    for (const occurrence of eventOccurrences) {
        const validation = validateReservationData({
            reservationData: occurrence,
            existingReservations: reservationsToCheck,
            users: []
        });

        if (!validation.isValid) {
            if (canSkipOverlaps && isOverlapValidationMessage(validation.message)) {
                skippedOccurrences.push({
                    ...occurrence,
                    skip_reason: validation.message
                });
                continue;
            }

            return {
                isValid: false,
                message: validation.message,
                creatableOccurrences: [],
                skippedOccurrences: []
            };
        }

        creatableOccurrences.push(occurrence);
        reservationsToCheck.push(occurrence);
    }

    if (creatableOccurrences.length === 0) {
        return {
            isValid: false,
            message: "Every recurring reservation overlaps with an existing reservation.",
            creatableOccurrences,
            skippedOccurrences
        };
    }

    return {
        isValid: true,
        message: "",
        creatableOccurrences,
        skippedOccurrences
    };
}

function isOverlapValidationMessage(message) {
    return String(message || "").startsWith(OVERLAP_VALIDATION_MESSAGE_PREFIX);
}

function confirmSkippedRecurringReservations(skippedOccurrences) {
    const skippedDateList = skippedOccurrences
        .map(formatSkippedOccurrence)
        .join("\n");

    return window.confirm(
        [
            "Some recurring reservation dates overlap with existing reservations.",
            "Those dates will be skipped:",
            "",
            skippedDateList,
            "",
            "Continue creating the remaining reservations?"
        ].join("\n")
    );
}

function formatSkippedOccurrence(occurrence) {
    const startDate = new Date(occurrence.start_time);
    const endDate = new Date(occurrence.end_time);

    if (Number.isNaN(startDate.getTime()) || Number.isNaN(endDate.getTime())) {
        return "- Date unavailable";
    }

    return `- ${formatReadableSkippedDate(startDate)}, ${formatTime(startDate)} - ${formatTime(endDate)}`;
}

function formatReadableSkippedDate(date) {
    return date.toLocaleDateString([], {
        weekday: "short",
        month: "short",
        day: "numeric",
        year: "numeric"
    });
}

function formatTime(date) {
    return date.toLocaleTimeString([], {
        hour: "numeric",
        minute: "2-digit"
    });
}
