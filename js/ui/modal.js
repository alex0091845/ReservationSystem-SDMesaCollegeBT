import { formatReadableDate, getEventDateTime } from "../utils/dateUtils.js";

export function createModalController(elements) {
    const {
        eventModalOverlay,
        modalEventTitle,
        modalEventDepartment,
        modalInstructor,
        modalDate,
        modalTime,
        modalDescription
    } = elements;

    // Display pop-up window with event info
    function openEventModal(eventData, dateKey) {
        const eventDate = getEventDateTime(dateKey, eventData.start);

        modalEventTitle.textContent = eventData.title;
        modalEventDepartment.textContent = eventData.department;
        modalInstructor.textContent = eventData.instructor;
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