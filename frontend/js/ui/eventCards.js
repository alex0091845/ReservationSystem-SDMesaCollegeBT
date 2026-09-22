import {
    formatReadableDate,
    getEventColorClass
} from "../utils/dateUtils.js";
import { createAttendeeCountBadge } from "./attendees.js";

export function createEventCard({
    event,
    classNames = [],
    titleFallback = "Untitled event",
    attendees = [],
    showAttendeeCount = false,
    onClick
}) {
    const card = document.createElement("button");
    const privateEvent = isPrivateEventForCurrentUser(event);

    card.type = "button";
    card.className = [
        "upcoming-event-card",
        ...normalizeClassNames(classNames),
        privateEvent ? "private-event-card" : getEventColorClass(event.event_type)
    ].join(" ");

    if (privateEvent) {
        card.appendChild(createEventCardText("upcoming-event-title", "Private event"));
    } else {
        card.append(
            createEventCardText("upcoming-event-title", event.title || titleFallback),
            createEventCardText("upcoming-event-date", formatEventDate(event)),
            createEventCardText("upcoming-event-time", formatEventTime(event))
        );
    }

    if (showAttendeeCount && !privateEvent) {
        card.appendChild(createAttendeeCountBadge(event, attendees));
    }

    if (onClick && !privateEvent) {
        card.addEventListener("click", () => onClick(event));
    }

    return card;
}

export function createWeekEventCard({
    event,
    onClick
}) {
    const card = document.createElement("button");
    const privateEvent = isPrivateEventForCurrentUser(event);

    card.type = "button";
    card.className = [
        "week-event",
        privateEvent ? "private-event-card" : getEventColorClass(event.event_type)
    ].join(" ");
    card.textContent = privateEvent ? "Private event" : (event.title || "Untitled event");

    if (onClick && !privateEvent) {
        card.addEventListener("click", clickEvent => {
            clickEvent.stopPropagation();
            onClick(event, clickEvent);
        });
    }

    return card;
}

function isPrivateEventForCurrentUser(event) {
    const role = sessionStorage.getItem("currentUserRole")?.toLowerCase();

    return event.is_public === false && role !== "faculty" && role !== "admin";
}

export function formatEventDate(event) {
    if (!event.start_time) {
        return "Date not set";
    }

    const startDate = new Date(event.start_time);

    if (Number.isNaN(startDate.getTime())) {
        return "Date not set";
    }

    return formatReadableDate(startDate);
}

export function formatEventTime(event) {
    if (!event.start_time || !event.end_time) {
        return "Time not set";
    }

    const startDate = new Date(event.start_time);
    const endDate = new Date(event.end_time);

    if (Number.isNaN(startDate.getTime()) || Number.isNaN(endDate.getTime())) {
        return "Time not set";
    }

    const startTime = startDate.toLocaleTimeString([], {
        hour: "numeric",
        minute: "2-digit"
    });

    const endTime = endDate.toLocaleTimeString([], {
        hour: "numeric",
        minute: "2-digit"
    });

    return `${startTime} - ${endTime}`;
}

function createEventCardText(className, textContent) {
    const element = document.createElement("div");

    element.className = className;
    element.textContent = textContent;

    return element;
}

function normalizeClassNames(classNames) {
    return Array.isArray(classNames)
        ? classNames.filter(Boolean)
        : String(classNames || "")
            .split(/\s+/)
            .filter(Boolean);
}
