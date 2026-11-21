import {
    CALENDAR_END_HOUR,
    CALENDAR_END_LABEL,
    CALENDAR_START_HOUR,
    getEventsForDay,
    getEventTypeKey,
    getVisibleEventSectionForDay,
    hourNames,
    monthNames
} from "../utils/dateUtils.js";
import { createWeekEventCard, formatEventTime } from "./eventCards.js";
import { addSwipe, isPhoneWidth } from "./swipe.js";
import { renderWeekStrip } from "./weekStrip.js";

const FULL_WEEKDAY_NAMES = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"];
const VISIBLE_HOURS = CALENDAR_END_HOUR - CALENDAR_START_HOUR;
// A card is never drawn shorter than 24px (zoom-calendar.css): about 21 minutes at 68px an hour,
// 24 minutes at 60px on a phone. Placing events by that drawn length keeps a very short event
// and the one right after it side by side instead of drawn on top of each other.
const MIN_DRAWN_MINUTES = 25;
const EVENT_TYPE_LABELS = {
    study_group: "Study group",
    meeting: "Meeting",
    workshop: "Workshop",
    social: "Social",
    other: "Other"
};
// The "Swipe to change day" hint fades over this long (see zoom-calendar.css), starting once it
// has been fully on screen for a second. While the page loads, the lists above the calendar fill
// in and push it down, so a hint that is on screen for a moment at the start doesn't count.
const SWIPE_HINT_MS = 5000;
const SWIPE_HINT_SETTLE_MS = 1000;
const CHEVRON_PATHS = { left: "M15 5l-7 7 7 7", right: "M9 5l7 7-7 7" };

// The hint shows on phones the first time the Day view appears and stays until it has faded or
// the day changes. The page redraws the same day more than once (for example when the events
// arrive), so this remembers the day it was shown for and when its fade started.
const swipeHint = { dateKey: null, fadeStartedAt: null, isDone: false };

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
    // Phones only (see zoom-calendar.css): the days of this week, above the Day header.
    renderWeekStrip({ container, selectedDate, today: now, onSelectDate });
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

    // Phones: swiping the schedule sideways opens the next or previous day.
    addSwipe(timeline, {
        name: "day-schedule",
        onSwipe: step => onSelectDate(addDays(selectedDate, step))
    });

    const hint = createSwipeHint(selectedDate);

    if (hint) {
        timeline.appendChild(hint);
    }

    container.appendChild(timeline);
}

// Returns the hint for this draw, or null once it is done. Screen readers skip it: they move
// by the arrows and the strip, which do the same as a swipe.
function createSwipeHint(selectedDate) {
    const dateKey = selectedDate.toDateString();

    if (swipeHint.isDone || !isPhoneWidth()) {
        return null;
    }

    if (swipeHint.dateKey === null) {
        swipeHint.dateKey = dateKey;
    } else if (swipeHint.dateKey !== dateKey) {
        swipeHint.isDone = true;
        return null;
    }

    const elapsed = swipeHint.fadeStartedAt === null ? 0 : performance.now() - swipeHint.fadeStartedAt;

    if (elapsed >= SWIPE_HINT_MS) {
        swipeHint.isDone = true;
        return null;
    }

    const hint = document.createElement("div");

    hint.classList.add("day-swipe-hint");
    hint.setAttribute("aria-hidden", "true");
    hint.append(createChevron("left"), "Swipe to change day", createChevron("right"));
    hint.addEventListener("animationend", () => {
        swipeHint.isDone = true;
        hint.remove();
    });

    if (swipeHint.fadeStartedAt !== null) {
        // A redraw: carry on the fade from where the last hint was.
        hint.style.animationDelay = `-${Math.round(elapsed)}ms`;
        hint.classList.add("is-fading");
    } else {
        // Wait until it can be seen, so it isn't over before anyone scrolls down to the calendar.
        startFadeWhenVisible(hint);
    }

    return hint;
}

function startFadeWhenVisible(hint) {
    let timer = null;
    const startFade = () => {
        if (swipeHint.fadeStartedAt === null) {
            swipeHint.fadeStartedAt = performance.now();
        }

        hint.classList.add("is-fading");
    };

    if (!("IntersectionObserver" in window)) {
        startFade();
        return;
    }

    const observer = new IntersectionObserver(entries => {
        const latest = entries[entries.length - 1];

        window.clearTimeout(timer);

        // A redraw replaced this hint; the new one has its own observer.
        if (!hint.isConnected) {
            observer.disconnect();
            return;
        }

        if (latest.isIntersecting && latest.intersectionRatio >= 0.99) {
            timer = window.setTimeout(() => {
                observer.disconnect();

                if (hint.isConnected) {
                    startFade();
                }
            }, SWIPE_HINT_SETTLE_MS);
        }
    }, { threshold: [0, 1] });

    observer.observe(hint);
}

function createChevron(direction) {
    const svgNamespace = "http://www.w3.org/2000/svg";
    const svg = document.createElementNS(svgNamespace, "svg");
    const path = document.createElementNS(svgNamespace, "path");

    svg.setAttribute("width", "14");
    svg.setAttribute("height", "14");
    svg.setAttribute("viewBox", "0 0 24 24");
    svg.setAttribute("fill", "none");
    svg.setAttribute("stroke", "currentColor");
    svg.setAttribute("stroke-width", "3");
    svg.setAttribute("stroke-linecap", "round");
    svg.setAttribute("stroke-linejoin", "round");
    path.setAttribute("d", CHEVRON_PATHS[direction]);
    svg.appendChild(path);

    return svg;
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
    button.classList.add("circle-NavBtn");
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
        const itemStart = item.section.start.getTime();
        const itemEnd = drawnEnd(item.section);

        if (group.length > 0 && itemStart >= groupEnd) {
            closeGroup();
        }

        const usedColumns = group
            .filter(other => drawnEnd(other.section) > itemStart)
            .map(other => other.column);
        let column = 0;

        while (usedColumns.includes(column)) {
            column++;
        }

        group.push({ ...item, column });
        groupEnd = groupEnd === null || itemEnd > groupEnd ? itemEnd : groupEnd;
    });

    if (group.length > 0) {
        closeGroup();
    }

    return placed;
}

// When the event ends, or when its card ends if the 24px minimum draws it longer than it is.
function drawnEnd(section) {
    return Math.max(section.end.getTime(), section.start.getTime() + (MIN_DRAWN_MINUTES * 60000));
}

function createDayEventCard({ event, section, column, columnCount }, now, openEventModal) {
    // Reuses the week view card, so private events stay hidden and unclickable by the same rule.
    const card = createWeekEventCard({
        event,
        onClick: openEventModal
    });
    const isPrivate = card.classList.contains("private-event-card");
    // The text goes in a body inside the card. The card is the container the short-event rules in
    // zoom-calendar.css measure, and a container can only restyle what is inside it.
    const body = document.createElement("span");
    const title = document.createElement("span");
    const name = document.createElement("span");

    card.classList.add("day-event");
    card.style.setProperty("--day-offset-hours", String(hoursFromDayStart(section.start)));
    card.style.setProperty("--day-duration-hours", String((section.end - section.start) / 3600000));
    card.style.setProperty("--day-column", String(column));
    card.style.setProperty("--day-column-count", String(columnCount));

    body.classList.add("day-event-body");
    title.classList.add("day-event-title");
    name.classList.add("day-event-name");
    title.appendChild(name);
    body.appendChild(title);

    if (isPrivate) {
        // Same words the week view shows.
        name.textContent = card.textContent;
        card.textContent = "";
        card.appendChild(body);

        return card;
    }

    const time = document.createElement("span");
    const details = document.createElement("span");
    const isHappeningNow = Boolean(now && section.start <= now && now < section.end);

    name.textContent = event.title || "Untitled event";
    time.classList.add("day-event-time");
    time.textContent = formatEventTime(event);
    details.classList.add("day-event-details");
    details.textContent = [
        EVENT_TYPE_LABELS[getEventTypeKey(event.event_type)] || "",
        event.department || ""
    ].filter(Boolean).join(" · ");

    if (isHappeningNow) {
        const nowBadge = document.createElement("span");

        nowBadge.classList.add("day-event-now");
        nowBadge.textContent = "Now";
        title.appendChild(nowBadge);
    }

    body.appendChild(time);

    if (details.textContent) {
        body.appendChild(details);
    }

    // A short card hides its time or details line, so the full text stays in the hover tooltip
    // and in the name screen readers announce.
    const fullText = [name.textContent, time.textContent, details.textContent].filter(Boolean).join(", ");

    card.title = fullText;
    card.setAttribute("aria-label", isHappeningNow ? `${fullText}, happening now` : fullText);
    card.textContent = "";
    card.appendChild(body);

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
