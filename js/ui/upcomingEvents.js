// import { reservedEvents } from "../data/events.js";
import { formatReadableDate, getEventDateTime } from "../utils/dateUtils.js";

export function renderUpcomingEvents(container, today, reservedEvents, openEventModal) {
    // Clears render
    container.innerHTML = "";

    // Stores events
    const allEvents = [];

    // Adds dateTime field to each event entry
    Object.entries(reservedEvents).forEach(([dateKey, events]) => {
        events.forEach(event => {
            allEvents.push({
                ...event,
                dateKey,
                dateTime: getEventDateTime(dateKey, event.start)
            });
        });
    });

    // Sorts events first to last
    allEvents.sort((a, b) => a.dateTime - b.dateTime);

    // Pulls 3 soonest events after today
    const upcoming = allEvents
        .filter(event => event.dateTime >= today)
        .slice(0, 3);

    if (upcoming.length === 0) {
        container.innerHTML = `<div class="upcoming-empty">No upcoming events scheduled.</div>`;
        return;
    }

    // Creates a card to display the 3 soonest events
    upcoming.forEach(event => {
        const card = document.createElement("button");
        card.type = "button";

        /* Uses the event's color field to stylize the card. Needs to be replaced. Potentially use a color for each department or event type*/
        card.className = `upcoming-event-card ${event.color}`;

        // Formats card text
        card.innerHTML = `
            <div class="upcoming-event-title">${event.title}</div>
            <div class="upcoming-event-date">${formatReadableDate(event.dateTime)}</div>
            <div class="upcoming-event-time">${event.start} - ${event.end}</div>
        `;

        card.addEventListener("click", () => {
            openEventModal(event, event.dateKey);
        });

        container.appendChild(card);
    });
}