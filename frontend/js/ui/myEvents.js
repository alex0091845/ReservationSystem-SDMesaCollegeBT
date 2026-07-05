import { createEventCard } from "./eventCards.js";

export function renderMyEvents({
    container,
    currentUser,
    reservedEvents,
    attendees = [],
    onEditEvent
}) {
    container.innerHTML = "";

    const userEvents = getEventsForUser(currentUser, reservedEvents);

    if (userEvents.length === 0) {
        const emptyState = document.createElement("div");

        emptyState.className = "selected-user-empty-reservations";
        emptyState.textContent = "No reservations found for this user.";

        container.appendChild(emptyState);
        return;
    }

    userEvents.forEach(event => {
        container.appendChild(
            createMyEventCard(event, attendees, onEditEvent)
        );
    });
}

function createMyEventCard(event, attendees, onEditEvent) {
    return createEventCard({
        event,
        classNames: ["selected-user-reservation-card"],
        titleFallback: "Untitled reservation",
        attendees,
        showAttendeeCount: true,
        onClick: onEditEvent
    });
}

function getEventsForUser(user, reservedEvents) {
    if (!user) {
        return [];
    }

    return reservedEvents.filter(event => {
        const eventHostId =
            event.host_user_id ??
            event.host_user?.id;

        if (
            eventHostId !== undefined &&
            user.id !== undefined &&
            String(eventHostId) === String(user.id)
        ) {
            return true;
        }

        const eventHostEmail = event.host_user?.email;

        return (
            eventHostEmail &&
            user.email &&
            eventHostEmail.toLowerCase() === user.email.toLowerCase()
        );
    });
}
