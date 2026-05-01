// import { reservedEvents } from "../data/events.js";

export const hourNames = [
    "8:00 AM",
    "9:00 AM",
    "10:00 AM",
    "11:00 AM",
    "12:00 PM",
    "1:00 PM",
    "2:00 PM",
    "3:00 PM",
    "4:00 PM",
    "5:00 PM",
    "6:00 PM",
    "7:00 PM",
    "8:00 PM"
];

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
    return reservedEvents[formatDateKey(year, month, day)] || [];
}

// Returns level 0-4 based on how many events scheduled for given date
export function getDensityClass(reservedEvents, year, month, day) {
    const count = getEventsForDay(reservedEvents, year, month, day).length;

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
    return ((end.hour * 60) + end.minute) - ((start.hour * 60) + start.minute);
}

// Returns processed event date that allows for easy sorting
export function getEventDateTime(dateKey, timeString) {
    const [year, month, day] = dateKey.split("-").map(Number);
    const parsed = parseTime(timeString);
    // JS uses 0-based month indexing: Jan. is 0, Feb. is 1,...
    return new Date(year, month - 1, day, parsed.hour, parsed.minute);
}

// Returns a user-friendly form of given date in the form "Wed, March 25, 2026"
export function formatReadableDate(dateObj) {
    return `${weekdayNames[dateObj.getDay()]}, ${monthNames[dateObj.getMonth()]} ${dateObj.getDate()}, ${dateObj.getFullYear()}`;
}

// Returns a user-friendly form of given date range
export function formatShortDateRange(startDate, endDate) {
    const sameMonth = startDate.getMonth() === endDate.getMonth() &&
        startDate.getFullYear() === endDate.getFullYear();
    const sameYear = startDate.getFullYear() === endDate.getFullYear();

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