import { createEventCard } from "./eventCards.js";

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

    upcoming.forEach(event => {
        container.appendChild(
            createEventCard({
                event,
                onClick: openEventModal
            })
        );
    });
}
