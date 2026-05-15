import {
    formatReadableDate,
    getEventColorClass
} from "../utils/dateUtils.js";

export function renderUpcomingEvents(
    container,
    today,
    reservedEvents,
    openEventModal
) {
    // Clears current render
    container.innerHTML = "";

    // Sorts events chronologically using backend timestamps
    const sortedEvents = [...reservedEvents].sort((a, b) => {
        return new Date(a.start_time) - new Date(b.start_time);
    });

    // Finds the next 3 upcoming events
    const upcoming = sortedEvents
        .filter(event => new Date(event.start_time) >= today)
        .slice(0, 3);

    // Empty state
    if (upcoming.length === 0) {
        container.innerHTML = `
            <div class="upcoming-empty">
                No upcoming events scheduled.
            </div>
        `;
        return;
    }

    // Creates event cards
    upcoming.forEach(event => {
        const card = document.createElement("button");

        card.type = "button";

        card.className = `
            upcoming-event-card
            ${getEventColorClass(event.event_type)}
        `;

        const startDate = new Date(event.start_time);
        const endDate = new Date(event.end_time);

        const formattedStartTime = startDate.toLocaleTimeString([], {
            hour: "numeric",
            minute: "2-digit"
        });

        const formattedEndTime = endDate.toLocaleTimeString([], {
            hour: "numeric",
            minute: "2-digit"
        });

        card.innerHTML = `
            <div class="upcoming-event-title">
                ${event.title}
            </div>

            <div class="upcoming-event-date">
                ${formatReadableDate(startDate)}
            </div>

            <div class="upcoming-event-time">
                ${formattedStartTime} - ${formattedEndTime}
            </div>
        `;

        card.addEventListener("click", () => {
            openEventModal(event);
        });

        container.appendChild(card);
    });
}