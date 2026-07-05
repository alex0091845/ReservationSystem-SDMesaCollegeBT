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

    card.type = "button";
    card.className = [
        "upcoming-event-card",
        ...normalizeClassNames(classNames),
        getEventColorClass(event.event_type)
    ].join(" ");

    card.append(
        createEventCardText("upcoming-event-title", event.title || titleFallback),
        createEventCardText("upcoming-event-date", formatEventDate(event)),
        createEventCardText("upcoming-event-time", formatEventTime(event))
    );

    if (showAttendeeCount) {
        card.appendChild(createAttendeeCountBadge(event, attendees));
    }

    if (onClick) {
        card.addEventListener("click", () => onClick(event));
    }

    return card;
}

export function createWeekEventCard({
    event,
    onClick
}) {
    const card = document.createElement("button");

    card.type = "button";
    card.className = [
        "week-event",
        getEventColorClass(event.event_type)
    ].join(" ");
    card.textContent = event.title || "Untitled event";

    if (onClick) {
        card.addEventListener("click", clickEvent => {
            clickEvent.stopPropagation();
            onClick(event, clickEvent);
        });
    }

    return card;
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
