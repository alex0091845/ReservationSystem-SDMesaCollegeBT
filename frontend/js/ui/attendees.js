export function getAttendeesForEvent(event, attendees = []) {
    const eventId = event?.id;

    if (eventId === undefined || eventId === null) {
        return [];
    }

    return attendees.filter(attendee => {
        const attendeeEventId =
            attendee.event_id ??
            attendee.event?.id;

        return String(attendeeEventId) === String(eventId);
    });
}

export function createAttendeeCountBadge(event, attendees = []) {
    const attendeeCount = getAttendeesForEvent(event, attendees).length;
    const badge = document.createElement("div");

    badge.className = "event-card-attendee-count";
    badge.textContent = formatAttendeeCount(attendeeCount);

    return badge;
}

export function renderEventAttendees({
    container,
    countElement,
    event,
    attendees = []
}) {
    const eventAttendees = getAttendeesForEvent(event, attendees);

    if (countElement) {
        countElement.textContent = formatAttendeeCount(eventAttendees.length);
    }

    if (!container) {
        return;
    }

    container.innerHTML = "";

    if (eventAttendees.length === 0) {
        const emptyState = document.createElement("div");

        emptyState.className = "reservation-attendees-empty";
        emptyState.textContent = "No attendees have checked in for this event.";

        container.appendChild(emptyState);
        return;
    }

    eventAttendees.forEach(attendee => {
        container.appendChild(createAttendeeItem(attendee));
    });
}

function createAttendeeItem(attendee) {
    const item = document.createElement("article");

    item.className = "reservation-attendee-item";

    const attendeeName = document.createElement("div");

    attendeeName.className = "reservation-attendee-name";
    attendeeName.textContent = getAttendeeName(attendee);

    const attendeeMeta = document.createElement("div");

    attendeeMeta.className = "reservation-attendee-meta";
    attendeeMeta.append(
        createMetaLine("Email", attendee.email || "Not provided"),
        createMetaLine("SDCCD ID", attendee.sdccd_id || "Not provided"),
        createMetaLine("Check-in", formatCheckInTime(attendee.check_in_time))
    );

    item.append(attendeeName, attendeeMeta);

    return item;
}

function createMetaLine(label, value) {
    const line = document.createElement("div");
    const labelElement = document.createElement("span");
    const valueElement = document.createElement("span");

    labelElement.className = "reservation-attendee-meta-label";
    labelElement.textContent = `${label}:`;
    valueElement.textContent = value;

    line.append(labelElement, " ", valueElement);

    return line;
}

function getAttendeeName(attendee) {
    return `${attendee.first_name || ""} ${attendee.last_name || ""}`.trim() ||
        "Unnamed attendee";
}

function formatAttendeeCount(attendeeCount) {
    return `${attendeeCount} attendee${attendeeCount === 1 ? "" : "s"}`;
}

function formatCheckInTime(checkInTime) {
    if (!checkInTime) {
        return "Not recorded";
    }

    const date = new Date(checkInTime);

    if (Number.isNaN(date.getTime())) {
        return "Not recorded";
    }

    return date.toLocaleString([], {
        month: "short",
        day: "numeric",
        year: "numeric",
        hour: "numeric",
        minute: "2-digit"
    });
}
