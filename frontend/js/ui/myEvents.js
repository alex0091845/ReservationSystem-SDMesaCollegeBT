import { createEventCard } from "./eventCards.js";

export function renderMyEvents({
    container,
    currentUser,
    reservedEvents,
    cancelledEvents = [],
    attendees = [],
    view = "upcoming",
    onEditEvent
}) {
    container.innerHTML = "";

    // A reservation stays in Upcoming until it ends. Cancelled ones sit in time order too,
    // labelled, so their check-ins stay reachable. Past puts the most recent on top.
    const now = new Date();
    const showPast = view === "past";
    const userEvents = getEventsForUser(currentUser, [...reservedEvents, ...cancelledEvents])
        .filter(event => (new Date(event.end_time) <= now) === showPast)
        .sort((a, b) => showPast
            ? new Date(b.start_time) - new Date(a.start_time)
            : new Date(a.start_time) - new Date(b.start_time));

    if (userEvents.length === 0) {
        const emptyState = document.createElement("div");

        emptyState.className = "selected-user-empty-reservations";
        emptyState.textContent = `No ${view} reservations.`;

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
