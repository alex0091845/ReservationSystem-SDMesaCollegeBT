import { getEventsForDay, monthNames, weekdayNames } from "../utils/dateUtils.js";
import { createWeekEventCard } from "./eventCards.js";

const FULL_WEEKDAY_NAMES = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"];
const MAX_EVENTS_PER_DAY = 2; // the rest are summed up as "+N more"

// Renders the Month zoom level: one big month, a row per week.
// Clicking anywhere in a week row zooms in to that week.
export function renderMonthStage({
    container,
    selectedDate,
    reservedEvents,
    onChangeMonth,
    onSelectWeek,
    openEventModal
}) {
    const year = selectedDate.getFullYear();
    const month = selectedDate.getMonth();

    container.innerHTML = "";
    container.appendChild(createMonthHeader(year, month, onChangeMonth));
    container.appendChild(createWeekdayRow());

    const grid = document.createElement("div");
    const firstDayIndex = new Date(year, month, 1).getDay();
    const daysInMonth = new Date(year, month + 1, 0).getDate();
    const weekCount = Math.ceil((firstDayIndex + daysInMonth) / 7);

    grid.classList.add("month-grid");

    for (let weekIndex = 0; weekIndex < weekCount; weekIndex++) {
        // Day-of-month of this row's Sunday. It can be below 1 or past the end of the
        // month; new Date() rolls those into the month before or after for us.
        const sundayDay = weekIndex * 7 - firstDayIndex + 1;

        grid.appendChild(createWeekRow({
            year,
            month,
            sundayDay,
            daysInMonth,
            selectedDate,
            reservedEvents,
            onSelectWeek,
            openEventModal
        }));
    }

    container.appendChild(grid);
}

function createMonthHeader(year, month, onChangeMonth) {
    const header = document.createElement("div");
    const prevButton = createMonthNavButton("←", "Previous month", () => onChangeMonth(-1));
    const nextButton = createMonthNavButton("→", "Next month", () => onChangeMonth(1));
    const title = document.createElement("h3");

    header.classList.add("month-header");
    title.classList.add("month-title");
    title.textContent = `${monthNames[month]} ${year}`;

    header.append(prevButton, title, nextButton);

    return header;
}

function createMonthNavButton(arrow, label, onClick) {
    const button = document.createElement("button");

    button.type = "button";
    button.classList.add("nav-btn");
    button.textContent = arrow;
    button.setAttribute("aria-label", label);
    button.addEventListener("click", onClick);

    return button;
}

function createWeekdayRow() {
    const row = document.createElement("div");

    row.classList.add("month-weekdays");
    row.setAttribute("aria-hidden", "true");

    weekdayNames.forEach(name => {
        const cell = document.createElement("span");

        cell.textContent = name;
        row.appendChild(cell);
    });

    return row;
}

function createWeekRow({
    year,
    month,
    sundayDay,
    daysInMonth,
    selectedDate,
    reservedEvents,
    onSelectWeek,
    openEventModal
}) {
    const row = document.createElement("div");
    const today = new Date();

    // A day of this row that belongs to the month on screen. Clicking a greyed-out
    // day from the month before or after selects this one, so the breadcrumb stays put.
    const anchorDay = Math.min(Math.max(sundayDay, 1), daysInMonth);

    row.classList.add("month-week");

    for (let offset = 0; offset < 7; offset++) {
        const day = sundayDay + offset;
        const isOutside = day < 1 || day > daysInMonth;
        const dateObj = new Date(year, month, day);
        const targetDate = isOutside ? new Date(year, month, anchorDay) : dateObj;
        const dayEvents = getEventsForDay(
            reservedEvents,
            dateObj.getFullYear(),
            dateObj.getMonth(),
            dateObj.getDate()
        );
        const cell = document.createElement("div");

        cell.classList.add("month-day");
        cell.classList.toggle("is-outside", isOutside);
        cell.classList.toggle("is-today", isSameDay(dateObj, today));
        cell.classList.toggle("is-selected", isSameDay(dateObj, selectedDate));
        cell.appendChild(createDayNumber(dateObj, isOutside, dayEvents.length));
        cell.appendChild(createDayEvents(dayEvents, openEventModal));
        cell.addEventListener("click", () => onSelectWeek(targetDate));

        row.appendChild(cell);
    }

    return row;
}

// The date number. Inside the month it is a real button, so the keyboard can reach it;
// its click bubbles up to the day cell, which does the zooming.
function createDayNumber(dateObj, isOutside, eventCount) {
    const number = document.createElement(isOutside ? "span" : "button");

    number.classList.add("month-day-number");
    number.textContent = String(dateObj.getDate());

    if (!isOutside) {
        number.type = "button";
        number.setAttribute(
            "aria-label",
            `${FULL_WEEKDAY_NAMES[dateObj.getDay()]}, ${monthNames[dateObj.getMonth()]} ${dateObj.getDate()}, ${describeEvents(eventCount)}. Zoom in to this week.`
        );
    }

    return number;
}

function createDayEvents(dayEvents, openEventModal) {
    const list = document.createElement("div");

    list.classList.add("month-day-events");

    dayEvents.slice(0, MAX_EVENTS_PER_DAY).forEach(event => {
        // The team's card builder decides what a private event may show, so that rule is reused, not copied.
        const card = createWeekEventCard({ event, onClick: openEventModal });

        card.classList.add("month-event");
        list.appendChild(card);
    });

    if (dayEvents.length > MAX_EVENTS_PER_DAY) {
        const more = document.createElement("span");

        more.classList.add("month-more");
        more.textContent = `+${dayEvents.length - MAX_EVENTS_PER_DAY} more`;
        list.appendChild(more);
    }

    return list;
}

function isSameDay(a, b) {
    return a.getFullYear() === b.getFullYear() &&
        a.getMonth() === b.getMonth() &&
        a.getDate() === b.getDate();
}

function describeEvents(count) {
    if (count === 0) return "no events";
    if (count === 1) return "1 event";

    return `${count} events`;
}
