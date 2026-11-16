// DEV ONLY: fake events for working on the calendar without the backend.
// main.js uses these ONLY when the real API call fails on localhost.
// Safe to delete once everyone runs the backend locally.
//
// Events are placed relative to TODAY, so the current month and week
// always have something to show, whatever day you open the page.
// Shape matches the `events` table in erd.sql.

const TEMPLATES = [
    { title: "CISC 190 Workshop", event_type: "workshop", department: "CISC", start: 10, hours: 2 },
    { title: "Python Study Group", event_type: "study_group", department: "CISC", start: 13, hours: 1.5 },
    { title: "BT Faculty Meeting", event_type: "meeting", department: "BUSE", start: 9, hours: 1 },
    { title: "Data Science Club Mixer", event_type: "social", department: "MATH", start: 15, hours: 2 },
    { title: "Lab Maintenance Window", event_type: "other", department: "IT", start: 8, hours: 1 },
    { title: "Resume Workshop", event_type: "workshop", department: "CAREER", start: 11, hours: 1 },
    { title: "SQL Study Session", event_type: "study_group", department: "CISC", start: 14, hours: 2 }
];

const HOSTS = [
    { id: 1, first_name: "Alex", last_name: "Chow", email: "achow@example.edu" },
    { id: 2, first_name: "Maria", last_name: "Lopez", email: "mlopez@example.edu" },
    { id: 3, first_name: "Sam", last_name: "Nguyen", email: "snguyen@example.edu" }
];

// Day offsets from today (negative = past). Repeats make some days busier,
// so the density shading (level-1 to level-4) has something to show.
const DAY_OFFSETS = [
    -60, -45, -44, -30, -21, -14, -10, -7, -6, -3, -2, -1,
    0, 0, 0, 1, 2, 2, 3, 4, 5, 5, 5, 5, 7, 8, 9, 12,
    14, 15, 16, 20, 21, 28, 35, 42, 50, 63, 75, 90
];

// Builds "2026-10-13T10:00:00" in LOCAL time (no "Z"), which is what the
// calendar compares against.
function toLocalIso(date) {
    const pad = n => String(n).padStart(2, "0");

    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
        `T${pad(date.getHours())}:${pad(date.getMinutes())}:00`;
}

export function getSampleEvents(today = new Date()) {
    return DAY_OFFSETS.map((offset, index) => {
        const template = TEMPLATES[index % TEMPLATES.length];
        const host = HOSTS[index % HOSTS.length];

        const start = new Date(
            today.getFullYear(),
            today.getMonth(),
            today.getDate() + offset,
            Math.floor(template.start),
            (template.start % 1) * 60
        );
        const end = new Date(start.getTime() + template.hours * 60 * 60 * 1000);

        return {
            id: 9000 + index,              // high ids so they never look like real rows
            host_user_id: host.id,
            host_user: host,
            start_time: toLocalIso(start),
            end_time: toLocalIso(end),
            event_type: template.event_type,
            title: template.title,
            description: "Sample event (dev data only).",
            department: template.department,
            is_public: index % 4 !== 0,    // every 4th event is private
            recurrence_group_id: null
        };
    });
}