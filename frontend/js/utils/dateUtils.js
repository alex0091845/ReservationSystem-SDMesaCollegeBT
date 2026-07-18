// import { reservedEvents } from "../data/events.js";

export const CALENDAR_START_HOUR = 8;
export const CALENDAR_END_HOUR = 17;
export const CALENDAR_END_LABEL = "5:00 PM";

export const hourNames = Array.from(
    { length: CALENDAR_END_HOUR - CALENDAR_START_HOUR },
    (_, index) => formatHourLabel(CALENDAR_START_HOUR + index)
);

export const monthNames = [
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December"
];

export const weekdayNames = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];

// Formats dates to YYYY-MM-DD to be used for event data queries
export function formatDateKey(year, month, day) {
    const m = String(month + 1).padStart(2, "0");
    const d = String(day).padStart(2, "0");

    return `${year}-${m}-${d}`;
}

// Gets array of events for the given date, if none returns empty array
export function getEventsForDay(reservedEvents, year, month, day) {
    const dayStart = new Date(year, month, day, 0, 0, 0, 0);
    const nextDayStart = new Date(year, month, day + 1, 0, 0, 0, 0);

    return reservedEvents.filter(event => {
        const startDate = parseEventDate(event.start_time);
        const endDate = parseEventDate(event.end_time);

        if (!startDate || !endDate) {
            return false;
        }

        return startDate < nextDayStart && endDate > dayStart;
    });
}

export function getVisibleEventSectionForDay(event, dateObj) {
    const startDate = parseEventDate(event.start_time);
    const endDate = parseEventDate(event.end_time);

    if (!startDate || !endDate) {
        return null;
    }

    const visibleDayStart = new Date(
        dateObj.getFullYear(),
        dateObj.getMonth(),
        dateObj.getDate(),
        CALENDAR_START_HOUR,
        0,
        0,
        0
    );
    const visibleDayEnd = new Date(
        dateObj.getFullYear(),
        dateObj.getMonth(),
        dateObj.getDate(),
        CALENDAR_END_HOUR,
        0,
        0,
        0
    );

    const sectionStart = new Date(
        Math.max(startDate.getTime(), visibleDayStart.getTime())
    );
    const sectionEnd = new Date(
        Math.min(endDate.getTime(), visibleDayEnd.getTime())
    );

    if (sectionEnd <= sectionStart) {
        return null;
    }

    return {
        start: sectionStart,
        end: sectionEnd
    };
}

// Returns level 0-4 based on how many events scheduled for given date
export function getDensityClass(reservedEvents, year, month, day) {
    const count = getEventsForDay(
        reservedEvents,
        year,
        month,
        day
    ).length;

    if (count === 0) return "level-0";
    if (count === 1) return "level-1";
    if (count === 2) return "level-2";
    if (count === 3) return "level-3";

    return "level-4";
}

// Converts timeString to { hour: h, minute: m }
export function parseTime(timeString) {
    if (
        timeString.includes(":") &&
        !timeString.toLowerCase().includes("am") &&
        !timeString.toLowerCase().includes("pm")
    ) {
        const [hour, minute] = timeString.split(":").map(Number);

        return { hour, minute };
    }

    const [time, modifier] = timeString.split(" ");
    let [hour, minute] = time.split(":").map(Number);

    // Converts 12-hour time to 24-hour time
    if (modifier === "PM" && hour !== 12) hour += 12;
    if (modifier === "AM" && hour === 12) hour = 0;

    return { hour, minute };
}

export function convertHourLabelTo24(label) {
    return parseTime(label).hour;
}

// Returns event duration in minutes
export function getDurationInMinutes(startTime, endTime) {
    const start = parseTime(startTime);
    const end = parseTime(endTime);

    return ((end.hour * 60) + end.minute) -
        ((start.hour * 60) + start.minute);
}

// Returns a user-friendly form of given date in the form "Wed, March 25, 2026"
export function formatReadableDate(dateObj) {
    return `${weekdayNames[dateObj.getDay()]}, ${monthNames[dateObj.getMonth()]} ${dateObj.getDate()}, ${dateObj.getFullYear()}`;
}

// Returns a user-friendly form of given date range
export function formatShortDateRange(startDate, endDate) {
    const sameMonth =
        startDate.getMonth() === endDate.getMonth() &&
        startDate.getFullYear() === endDate.getFullYear();

    const sameYear =
        startDate.getFullYear() === endDate.getFullYear();

    // Returns date in the form "March 25-26, 2026"
    if (sameMonth) {
        return `${monthNames[startDate.getMonth()]} ${startDate.getDate()}–${endDate.getDate()}, ${startDate.getFullYear()}`;
    }

    // Returns date in the form "March 25 - April 25, 2026"
    if (sameYear) {
        return `${monthNames[startDate.getMonth()]} ${startDate.getDate()} – ${monthNames[endDate.getMonth()]} ${endDate.getDate()}, ${startDate.getFullYear()}`;
    }

    // Returns date in the form "March 25, 2026 - March 25, 2027"
    return `${monthNames[startDate.getMonth()]} ${startDate.getDate()}, ${startDate.getFullYear()} – ${monthNames[endDate.getMonth()]} ${endDate.getDate()}, ${endDate.getFullYear()}`;
}

// Sort events and store them in reservedEvents
export function sortReservedEvents(reservedEvents) {
    reservedEvents.sort((a, b) => {
        return (
            new Date(a.start_time) -
            new Date(b.start_time)
        );
    });
}

// Displays color based on event type (event type is also displayed in text in the modal window), previously color was defined as a data field
export function getEventColorClass(eventType) {
    switch (eventType?.toLowerCase()) {
        case "study_group":
            return "green";

        case "meeting":
            return "blue";

        case "workshop":
            return "orange";

        case "social":
            return "purple";

        case "other":
            return "red";

        default:
            return "red";
    }
}

function formatHourLabel(hour) {
    const displayHour = hour % 12 || 12;
    const modifier = hour >= 12 ? "PM" : "AM";

    return `${displayHour}:00 ${modifier}`;
}

function parseEventDate(value) {
    if (!value) {
        return null;
    }

    const date = new Date(value);

    return Number.isNaN(date.getTime()) ? null : date;
}
