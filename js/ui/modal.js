import { formatReadableDate, getEventDateTime } from "../utils/dateUtils.js";

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
        modalOrganizer,
        modalDate,
        modalTime,
        modalDescription
    } = elements;

    // Display pop-up window with event info
    function openEventModal(eventData, dateKey) {
        const eventDate = getEventDateTime(dateKey, eventData.start);

        modalEventTitle.textContent = eventData.title;
        modalEventDepartment.textContent = eventData.department;
        modalOrganizer.textContent = eventData.organizer;
        modalDate.textContent = formatReadableDate(eventDate);
        modalTime.textContent = `${eventData.start} - ${eventData.end}`;
        modalDescription.textContent = eventData.description;

        eventModalOverlay.classList.add("active");
        eventModalOverlay.setAttribute("aria-hidden", "false");
    }

    function closeEventModal() {
        eventModalOverlay.classList.remove("active");
        eventModalOverlay.setAttribute("aria-hidden", "true");
    }

    return {
        openEventModal,
        closeEventModal
    };
}

function openReservationModal() {
    reservationModalOverlay.classList.add("active");
    reservationModalOverlay.setAttribute("aria-hidden", "false");
}

function closeReservationModal() {
    reservationModalOverlay.classList.remove("active");
    reservationModalOverlay.setAttribute("aria-hidden", "true");
    reservationForm.reset();
}

if (openReservationModalBtn) {
    openReservationModalBtn.addEventListener("click", openReservationModal);
}

reservationModalCloseBtn.addEventListener("click", closeReservationModal);
reservationCancelBtn.addEventListener("click", closeReservationModal);

// Allows exiting modal with click
reservationModalOverlay.addEventListener("click", (event) => {
    const clickedInsideModal = event.target.closest(".reservation-modal");

    if (!clickedInsideModal) {
        closeReservationModal();
    }
});

// Allows exiting modal with escape key
document.addEventListener("keydown", (event) => {
    if (event.key === "Escape" && reservationModalOverlay.classList.contains("active")) {
        closeReservationModal();
    }
});

// Sends data back after form is completed
reservationForm.addEventListener("submit", (event) => {
    event.preventDefault();
    // FILL THIS OUT
    closeReservationModal();
});