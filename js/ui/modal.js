import { createEvent } from "../api.js";
import { formatReadableDate } from "../utils/dateUtils.js";

const reservationModalOverlay = document.getElementById("reservationModalOverlay");
const openReservationModalBtn = document.getElementById("openReservationModalBtn");
const reservationModalCloseBtn = document.getElementById("reservationModalCloseBtn");
const reservationCancelBtn = document.getElementById("reservationCancelBtn");
const reservationForm = document.getElementById("reservationForm");

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
        modalIsPublic
    } = elements;

    // Opens event details modal
    function openEventModal(eventData) {
        const startDate = new Date(eventData.start_time);
        const endDate = new Date(eventData.end_time);

        console.log(eventData);
        modalEventTitle.textContent = eventData.title;
        modalEventDepartment.textContent = eventData.department;
        modalHost.textContent =
            eventData.host_user.first_name + " " + eventData.host_user.last_name;

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

        eventModalOverlay.classList.add("active");
        eventModalOverlay.setAttribute("aria-hidden", "false");
    }

    // Closes event details modal
    function closeEventModal() {
        eventModalOverlay.classList.remove("active");
        eventModalOverlay.setAttribute("aria-hidden", "true");
    }

    return {
        openEventModal,
        closeEventModal
    };
}

// Opens reservation creation modal
function openReservationModal() {
    reservationModalOverlay.classList.add("active");
    reservationModalOverlay.setAttribute("aria-hidden", "false");
}

// Closes reservation creation modal
function closeReservationModal() {
    reservationModalOverlay.classList.remove("active");
    reservationModalOverlay.setAttribute("aria-hidden", "true");

    reservationForm.reset();
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
reservationModalOverlay.addEventListener("click", (event) => {
    const clickedInsideModal =
        event.target.closest(".reservation-modal");

    if (!clickedInsideModal) {
        closeReservationModal();
    }
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

    const formData = new FormData(reservationForm);

    const startValue = formData.get("start");
    const endValue = formData.get("end");

    const start_time = new Date(startValue).toISOString();
    const end_time = new Date(endValue).toISOString();

    const eventData = {
        host_user_id: 1, // TODO: NEEDS TO BE PULLED AUTOMATICALLY FROM SIGNED IN USER
        start_time,
        end_time,
        event_type: formData.get("type"),
        description: formData.get("description"),
        title: formData.get("title"),
        department: formData.get("department"),
        is_public: formData.get("access") === "open"
    };

    try {
        await createEvent(eventData);

        closeReservationModal();

        // Temporary refresh until state-based refresh is added
        window.location.reload();
    } catch (error) {
        console.error("Error creating reservation:", error);
    }
});