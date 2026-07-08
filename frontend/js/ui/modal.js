import { createAttendee, createEvent, getEventTypes, getEvents, isUserDisabled } from "../api.js";
import { formatReadableDate } from "../utils/dateUtils.js";
import { validateReservationData } from "../utils/reservationValidation.js";
import { renderEventTypeOptions } from "./eventTypeOptions.js";

const reservationModalOverlay = document.getElementById("reservationModalOverlay");
const openReservationModalBtn = document.getElementById("openReservationModalBtn");
const reservationModalCloseBtn = document.getElementById("reservationModalCloseBtn");
const reservationCancelBtn = document.getElementById("reservationCancelBtn");
const reservationForm = document.getElementById("reservationForm");
const reservationSubmitBtn = reservationForm?.querySelector('button[type="submit"]');
const reservationStatus = document.getElementById("reservationStatus");
const reservationTypeSelect = document.getElementById("reservationType");
const currentHostUserId = sessionStorage.getItem("currentUserId") || 1;
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
function closeReservationModal() {
    restoreFocusBeforeHide(
        reservationModalOverlay,
        reservationModalReturnFocusElement
    );

    reservationModalOverlay.classList.remove("active");
    reservationModalOverlay.setAttribute("aria-hidden", "true");

    reservationForm.reset();
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

function toIsoDateTimeValue(value) {
    if (!value) {
        return "";
    }

    const date = new Date(value);

    return Number.isNaN(date.getTime()) ? "" : date.toISOString();
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

    const startValue = formData.get("start");
    const endValue = formData.get("end");

    const start_time = toIsoDateTimeValue(startValue);
    const end_time = toIsoDateTimeValue(endValue);

    const eventData = {
        host_user_id: currentHostUserId,
        start_time,
        end_time,
        event_type: formData.get("type"),
        description: formData.get("description"),
        title: formData.get("title"),
        department: formData.get("department"),
        is_public: formData.get("access") === "open"
    };

    setReservationStatus("");
    setReservationSubmitting(true);

    try {
        const existingReservations = await getEvents();
        const validation = validateReservationData({
            reservationData: eventData,
            existingReservations,
            users: []
        });

        if (!validation.isValid) {
            setReservationStatus(validation.message, "error");
            return;
        }

        const createdEvent = await createEvent(eventData);

        closeReservationModal();

        window.dispatchEvent(new CustomEvent("reservation:created", {
            detail: {
                reservation: createdEvent || eventData
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
