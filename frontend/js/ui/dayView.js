import {
    CALENDAR_END_HOUR,
    CALENDAR_END_LABEL,
    CALENDAR_START_HOUR,
    getEventsForDay,
    getVisibleEventSectionForDay,
    hourNames,
    monthNames
} from "../utils/dateUtils.js";
import { createWeekEventCard, formatEventTime } from "./eventCards.js";

const FULL_WEEKDAY_NAMES = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"];
const VISIBLE_HOURS = CALENDAR_END_HOUR - CALENDAR_START_HOUR;
const EVENT_TYPE_LABELS = {
    study_group: "Study group",
    meeting: "Meeting",
    workshop: "Workshop",
    social: "Social",
    other: "Other"
};

// Renders the Day zoom level: one day as a timeline, with events that overlap placed side by side.
export function renderDayView({
    container,
    selectedDate,
    reservedEvents,
    onSelectDate,
    openEventModal
}) {
    const now = new Date();
    const isToday = isSameDay(selectedDate, now);
    const events = getEventsForDay(
        reservedEvents,
        selectedDate.getFullYear(),
        selectedDate.getMonth(),
        selectedDate.getDate()
    );
    const placedEvents = placeEventsInColumns(
        events
            .map(event => ({
                event,
                section: getVisibleEventSectionForDay(event, selectedDate)
            }))
            .filter(({ section }) => section)
    );

    container.innerHTML = "";
    container.appendChild(createDayHeader(selectedDate, isToday, onSelectDate));
    container.appendChild(createDaySummary(placedEvents.length, isToday));

    const timeline = document.createElement("div");
    const column = document.createElement("div");

    timeline.classList.add("day-timeline");
    timeline.style.setProperty("--day-visible-hours", String(VISIBLE_HOURS));
    column.classList.add("day-column");

    timeline.appendChild(createHourLabels());

    for (let hourIndex = 0; hourIndex <= VISIBLE_HOURS; hourIndex++) {
        const line = document.createElement("div");

        line.classList.add("day-hour-line");
        line.style.setProperty("--day-offset-hours", String(hourIndex));
        column.appendChild(line);
    }

    placedEvents.forEach(placed => {
        column.appendChild(createDayEventCard(placed, isToday ? now : null, openEventModal));
    });

    if (isToday) {
        const nowLine = createNowLine(now);

        if (nowLine) {
            column.appendChild(nowLine);
        }
    }

    timeline.appendChild(column);
    container.appendChild(timeline);
}

function createDayHeader(selectedDate, isToday, onSelectDate) {
    const header = document.createElement("div");
    const title = document.createElement("h3");

    header.classList.add("day-header");
    title.classList.add("day-title");
    title.textContent =
        `${FULL_WEEKDAY_NAMES[selectedDate.getDay()]}, ${monthNames[selectedDate.getMonth()]} ${selectedDate.getDate()}, ${selectedDate.getFullYear()}`;

    header.append(
        createDayNavButton("←", "Previous day", () => onSelectDate(addDays(selectedDate, -1))),
        title,
        createDayNavButton("→", "Next day", () => onSelectDate(addDays(selectedDate, 1)))
    );

    return header;
}

function createDayNavButton(arrow, label, onClick) {
    const button = document.createElement("button");

    button.type = "button";
    button.classList.add("nav-btn");
    button.textContent = arrow;
    button.setAttribute("aria-label", label);
    button.addEventListener("click", onClick);

    return button;
}

function createDaySummary(eventCount, isToday) {
    const summary = document.createElement("p");
    const countText = eventCount === 0
        ? "No events scheduled"
        : `${eventCount} ${eventCount === 1 ? "event" : "events"}`;

    summary.classList.add("day-summary");
    summary.textContent = countText;

    if (isToday) {
        const todayBadge = document.createElement("span");

        todayBadge.classList.add("day-today-badge");
        todayBadge.textContent = "Today";
        summary.appendChild(todayBadge);
    }

    return summary;
}

function createHourLabels() {
    const labels = document.createElement("div");

    labels.classList.add("day-hour-labels");
    labels.setAttribute("aria-hidden", "true");

    [...hourNames, CALENDAR_END_LABEL].forEach((hourLabel, hourIndex) => {
        const label = document.createElement("span");

        label.classList.add("day-hour-label");
        label.style.setProperty("--day-offset-hours", String(hourIndex));
        label.textContent = hourLabel;
        labels.appendChild(label);
    });

    return labels;
}

// Gives each event a column so events that overlap in time sit side by side instead of on top of each other.
function placeEventsInColumns(items) {
    const sorted = [...items].sort((first, second) => {
        return (first.section.start - second.section.start) || (second.section.end - first.section.end);
    });
    const placed = [];
    let group = [];
    let groupEnd = null;

    const closeGroup = () => {
        const columnCount = Math.max(...group.map(item => item.column)) + 1;

        group.forEach(item => placed.push({ ...item, columnCount }));
        group = [];
        groupEnd = null;
    };

    sorted.forEach(item => {
        if (group.length > 0 && item.section.start >= groupEnd) {
            closeGroup();
        }

        const usedColumns = group
            .filter(other => other.section.end > item.section.start)
            .map(other => other.column);
        let column = 0;

        while (usedColumns.includes(column)) {
            column++;
        }

        group.push({ ...item, column });
        groupEnd = groupEnd === null || item.section.end > groupEnd ? item.section.end : groupEnd;
    });

    if (group.length > 0) {
        closeGroup();
    }

    return placed;
}

function createDayEventCard({ event, section, column, columnCount }, now, openEventModal) {
    // Reuses the week view card, so private events stay hidden and unclickable by the same rule.
    const card = createWeekEventCard({
        event,
        onClick: openEventModal
    });
    const isPrivate = card.classList.contains("private-event-card");

    card.classList.add("day-event");
    card.style.setProperty("--day-offset-hours", String(hoursFromDayStart(section.start)));
    card.style.setProperty("--day-duration-hours", String((section.end - section.start) / 3600000));
    card.style.setProperty("--day-column", String(column));
    card.style.setProperty("--day-column-count", String(columnCount));

    if (isPrivate) {
        // Same words the week view shows.
        const label = document.createElement("span");

        label.classList.add("day-event-title");
        label.textContent = card.textContent;
        card.textContent = "";
        card.appendChild(label);

        return card;
    }

    const title = document.createElement("span");
    const time = document.createElement("span");
    const details = document.createElement("span");

    title.classList.add("day-event-title");
    title.textContent = event.title || "Untitled event";
    time.classList.add("day-event-time");
    time.textContent = formatEventTime(event);
    details.classList.add("day-event-details");
    details.textContent = [
        EVENT_TYPE_LABELS[event.event_type?.toLowerCase()] || "",
        event.department || ""
    ].filter(Boolean).join(" · ");

    if (now && section.start <= now && now < section.end) {
        const nowBadge = document.createElement("span");

        nowBadge.classList.add("day-event-now");
        nowBadge.textContent = "Now";
        title.appendChild(nowBadge);
    }

    card.textContent = "";
    card.append(title, time);

    if (details.textContent) {
        card.appendChild(details);
    }

    return card;
}

function createNowLine(now) {
    const offsetHours = hoursFromDayStart(now);

    if (offsetHours < 0 || offsetHours >= VISIBLE_HOURS) {
        return null;
    }

    const line = document.createElement("div");

    line.classList.add("day-now-line");
    line.setAttribute("aria-hidden", "true");
    line.style.setProperty("--day-offset-hours", String(offsetHours));

    return line;
}

function hoursFromDayStart(dateObj) {
    return dateObj.getHours() + (dateObj.getMinutes() / 60) - CALENDAR_START_HOUR;
}

function addDays(dateObj, dayOffset) {
    return new Date(dateObj.getFullYear(), dateObj.getMonth(), dateObj.getDate() + dayOffset);
}

function isSameDay(first, second) {
    return first.getFullYear() === second.getFullYear() &&
        first.getMonth() === second.getMonth() &&
        first.getDate() === second.getDate();
}
