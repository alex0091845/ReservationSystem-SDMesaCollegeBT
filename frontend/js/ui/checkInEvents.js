import {
    formatDateKey
} from "../utils/dateUtils.js";
import { createEventCard } from "./eventCards.js";

export function renderCheckInEvents({
    container,
    today,
    reservedEvents,
    openEventModal
}) {
    container.innerHTML = "";

    const todaysEvents = getTodaysEvents(today, reservedEvents);

    if (todaysEvents.length === 0) {
        const emptyState = document.createElement("div");

        emptyState.className = "check-in-widget-empty";
        emptyState.textContent = "No events scheduled for today.";

        container.appendChild(emptyState);
        return;
    }

    todaysEvents.forEach(event => {
        container.appendChild(
            createCheckInEventCard(event, openEventModal)
        );
    });
}

function getTodaysEvents(today, reservedEvents) {
    const todayKey = formatDateKey(
        today.getFullYear(),
        today.getMonth(),
        today.getDate()
    );

    return [...reservedEvents]
        .filter(event => {
            const eventDate = new Date(event.start_time);

            if (Number.isNaN(eventDate.getTime())) {
                return false;
            }

            return formatDateKey(
                eventDate.getFullYear(),
                eventDate.getMonth(),
                eventDate.getDate()
            ) === todayKey;
        })
        .sort((firstEvent, secondEvent) => {
            return new Date(firstEvent.start_time) - new Date(secondEvent.start_time);
        });
}

function createCheckInEventCard(event, openEventModal) {
    return createEventCard({
        event,
        classNames: ["check-in-widget-card"],
        onClick: openEventModal
    });
}
