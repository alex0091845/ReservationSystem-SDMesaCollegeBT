import { validateReservationData } from "./utils/reservationValidation.js";

const BASE_URL = "http://18.223.249.15:8080/api";
const DISABLED_USER_IDS_STORAGE_KEY = "disabledUserIds";
const EVENT_OVERRIDES_STORAGE_KEY = "eventOverrides";
const ATTENDEE_OVERRIDES_STORAGE_KEY = "attendeeOverrides";

// REWORK ALL API FUNCTIONS AND 

function getDisabledUserIds() {
    try {
        if (typeof sessionStorage === "undefined") {
            return [];
        }

        const storedValue = sessionStorage.getItem(DISABLED_USER_IDS_STORAGE_KEY);
        const parsedValue = JSON.parse(storedValue || "[]");

        return Array.isArray(parsedValue)
            ? parsedValue.map(String)
            : [];
    } catch (error) {
        console.error("Could not read disabled users:", error);
        return [];
    }
}

function saveDisabledUserIds(userIds) {
    if (typeof sessionStorage === "undefined") {
        return;
    }

    sessionStorage.setItem(
        DISABLED_USER_IDS_STORAGE_KEY,
        JSON.stringify([...new Set(userIds.map(String))])
    );
}

export async function isUserDisabled(userId) {
    const user = (await getUsers()).find(testUser => {
        return String(testUser.id) === String(userId);
    });

    if (user) {
        return user.enabled === false;
    }

    return getDisabledUserIds().includes(String(userId));
}

function getEventOverrides() {
    try {
        if (typeof sessionStorage === "undefined") {
            return [];
        }

        const storedValue = sessionStorage.getItem(EVENT_OVERRIDES_STORAGE_KEY);
        const parsedValue = JSON.parse(storedValue || "[]");

        return Array.isArray(parsedValue) ? parsedValue : [];
    } catch (error) {
        console.error("Could not read event overrides:", error);
        return [];
    }
}

function saveEventOverride(eventData) {
    if (typeof sessionStorage === "undefined") {
        return eventData;
    }

    const eventOverrides = getEventOverrides();
    const updatedEventOverrides = [
        ...eventOverrides.filter(event => String(event.id) !== String(eventData.id)),
        eventData
    ];

    sessionStorage.setItem(
        EVENT_OVERRIDES_STORAGE_KEY,
        JSON.stringify(updatedEventOverrides)
    );

    return eventData;
}

function getLocalAttendees() {
    try {
        if (typeof sessionStorage === "undefined") {
            return [];
        }

        const storedValue = sessionStorage.getItem(ATTENDEE_OVERRIDES_STORAGE_KEY);
        const parsedValue = JSON.parse(storedValue || "[]");

        return Array.isArray(parsedValue) ? parsedValue : [];
    } catch (error) {
        console.error("Could not read local attendees:", error);
        return [];
    }
}

function saveLocalAttendee(attendeeData) {
    if (typeof sessionStorage === "undefined") {
        return attendeeData;
    }

    const localAttendees = getLocalAttendees();
    const attendeeToStore = {
        ...attendeeData,
        id: attendeeData.id ?? getNextLocalAttendeeId(localAttendees),
        check_in_time: attendeeData.check_in_time ?? new Date().toISOString(),
        sdccd_id: attendeeData.sdccd_id || null
    };

    sessionStorage.setItem(
        ATTENDEE_OVERRIDES_STORAGE_KEY,
        JSON.stringify([
            ...localAttendees,
            attendeeToStore
        ])
    );

    return attendeeToStore;
}

function getNextLocalAttendeeId(localAttendees) {
    const maxLocalId = localAttendees.reduce((maxId, attendee) => {
        const attendeeId = Number(attendee.id);

        return Number.isNaN(attendeeId)
            ? maxId
            : Math.max(maxId, attendeeId);
    }, 0);

    return Math.max(Date.now(), maxLocalId + 1);
}

function mergeLocalAttendees(attendees) {
    const localAttendees = getLocalAttendees();
    const attendeeIds = new Set(
        attendees
            .map(attendee => attendee.id)
            .filter(attendeeId => attendeeId !== undefined && attendeeId !== null)
            .map(String)
    );

    return [
        ...attendees,
        ...localAttendees.filter(attendee => {
            return !attendeeIds.has(String(attendee.id));
        })
    ];
}

function getNextEventId(events) {
    return events.reduce((maxId, event) => {
        const eventId = Number(event.id);

        return Number.isNaN(eventId)
            ? maxId
            : Math.max(maxId, eventId);
    }, 0) + 1;
}

function getEventHostUser(user) {
    if (!user) {
        return null;
    }

    return {
        id: user.id,
        email: user.email,
        first_name: user.first_name,
        last_name: user.last_name,
        phone: user.phone,
        role_name: user.role_name,
        enabled: user.enabled !== false
    };
}

function getEventHostById(hostUserId, users) {
    return getEventHostUser(users.find(user => {
        return String(user.id) === String(hostUserId);
    }));
}

function attachHostUser(event, users) {
    const hostUser = getEventHostById(event.host_user_id, users);

    return {
        ...event,
        host_user: hostUser || {
            id: event.host_user_id
        }
    };
}

function getBaseEvents() {
    return [{
        "id": 1,
        "host_user_id": 2,
        "start_time": new Date("2026-05-28T10:00:00"),
        "end_time": new Date("2026-05-28T11:00:00"),
        "event_type": "Study",
        "description": "The quick brown fox jumps over the lazy dog.",
        "title": "Team Meeting",
        "department": "Computer Science",
        "is_public": true
    },
    {
        "id": 2,
        "host_user_id": 1,
        "start_time": new Date("2026-05-16T10:00:00"),
        "end_time": new Date("2026-05-16T11:00:00"),
        "event_type": "Work",
        "description": "The quick brown fox jumps over the lazy dog.",
        "title": "Work",
        "department": "Computer Science",
        "is_public": true
        },
    {
        "id": 3,
        "host_user_id": 3,
        "start_time": new Date("2026-05-27T10:00:00"),
        "end_time": new Date("2026-05-27T11:00:00"),
        "event_type": "Work",
        "description": "The quick brown fox jumps over the lazy dog.",
        "title": "Work",
        "department": "Computer Science",
        "is_public": true
    }
    ];
}

function applyLocalEventOverrides(events) {
    const eventOverrides = getEventOverrides();
    const mergedEvents = events.map(event => {
        const override = eventOverrides.find(
            storedEvent => String(storedEvent.id) === String(event.id)
        );

        return override || event;
    });

    const storedEvents = eventOverrides.filter(storedEvent => {
        return !events.some(event => String(event.id) === String(storedEvent.id));
    });

    return [
        ...mergedEvents,
        ...storedEvents
    ];
}

function applyLocalUserStatus(users) {
    const disabledUserIds = getDisabledUserIds();

    return users.map(user => ({
        ...user,
        enabled: disabledUserIds.includes(String(user.id))
            ? false
            : user.enabled !== false
    }));
}

// cookie-cutter request helper called by all data functions
async function request(endpoint, method = "GET", data = null) {
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 2000);

    const options = {
        method,
        // credentials: "include",
        headers: {
            "Content-Type": "application/json"
        },
        signal: controller.signal
    };

    if (data && method !== "GET") {
        options.body = JSON.stringify(data);
    }

    try {
        const response = await fetch(`${BASE_URL}${endpoint}`, options);
        const responseData = response.status === 204
            ? null
            : await response.json();

        if (!response.ok) {
            throw new Error(`Request failed: ${method} ${endpoint}`);
        }

        return responseData;
    } finally {
        clearTimeout(timeoutId);
    }
}

export async function getEvents() {
    let events;

    try {
        events = await request("/events", "GET");

        if (!Array.isArray(events)) {
            throw new Error("Events response was not an array.");
        }
    } catch (error) {
        console.error("Could not load events from backend. Using local events:", error);
        events = getBaseEvents();
    }

    const users = await getUsers();

    return applyLocalEventOverrides(events)
        .map(event => attachHostUser(event, users));
}

export async function createEvent(eventData) {
    const users = await getUsers();
    const events = await getEvents();
    const validation = validateReservationData({
        reservationData: eventData,
        existingReservations: events,
        users
    });

    if (!validation.isValid) {
        throw new Error(validation.message);
    }

    const hostUser = getEventHostById(eventData.host_user_id, users);

    const eventForBackend = {
        ...eventData,
        host_user_id: hostUser?.id ?? eventData.host_user_id
    };

    try {
        const createdEvent = await request("/events", "POST", eventForBackend);

        return attachHostUser(createdEvent || eventForBackend, users);
    } catch (error) {
        console.error("Could not create event on backend. Saving locally:", error);
    }

    const createdEvent = attachHostUser({
        ...eventForBackend,
        id: getNextEventId(events)
    }, users);

    return saveEventOverride(createdEvent);
}

export async function deleteEvent(eventData) {
    return request("/events", "DELETE", eventData);
}

export async function updateEvent(eventData) {
    const users = await getUsers();
    const events = await getEvents();
    const validation = validateReservationData({
        reservationData: eventData,
        existingReservations: events,
        users,
        requireId: true
    });

    if (!validation.isValid) {
        throw new Error(validation.message);
    }

    try {
        const updatedEvent = await request("/events", "PATCH", eventData);

        return attachHostUser(updatedEvent || eventData, users);
    } catch (error) {
        console.error("Could not update event on backend. Saving locally:", error);
    }

    return saveEventOverride(eventData);
}

export async function createUser(userData) {
    return request("/users", "POST", userData);
}

export async function loginUser(email, password) {
    return request("/login", "POST", {
        email,
        password
    });
}

export async function logoutUser() {
    return request("/logout", "POST");
}

export async function getCurrentSession() {
    return request("/session", "GET");
}

// NOT DOCUMENTED
function getMockUsers() {
    return [
        {
            id: 1,
            email: "alex@sdccd.edu",
            password_hash: "pass",
            first_name: "Alex",
            last_name: "Chow",
            phone: "5551212",
            role_name: "Faculty",
            enabled: true
        },
        {
            id: 2,
            email: "leo@sdccd.edu",
            password_hash: "pass",
            first_name: "Leo",
            last_name: "Nguyen",
            phone: "5551212",
            role_name: "Faculty",
            enabled: true
        },
        {
            id: 3,
            email: "jordan@sdccd.edu",
            password_hash: "pass",
            first_name: "Jordan",
            last_name: "Ayling",
            phone: "5551212",
            role_name: "Faculty",
            enabled: true
        },
        {
            id: 4,
            email: "admin",
            password_hash: "admin",
            first_name: "admin",
            last_name: "admin",
            phone: "5551212",
            role_name: "Admin",
            enabled: true
        },
        {
            id: 5,
            email: "allan@sdccd.edu",
            password_hash: "pass",
            first_name: "Allan",
            last_name: "Schougaard",
            phone: "5551212",
            role_name: "Faculty",
            enabled: true
        },
        {
            id: 6,
            email: "braulio@sdccd.edu",
            password_hash: "pass",
            first_name: "Braulio",
            last_name: "Ochoa",
            phone: "5551212",
            role_name: "Faculty",
            enabled: true
        },
        {
            id: 7,
            email: "dominic@sdccd.edu",
            password_hash: "pass",
            first_name: "Dominic",
            last_name: "Last Name",
            phone: "5551212",
            role_name: "Faculty",
            enabled: true
        },
        {
            id: 8,
            email: "nathan@sdccd.edu",
            password_hash: "pass",
            first_name: "Nathan",
            last_name: "Last Name",
            phone: "5551212",
            role_name: "Faculty",
            enabled: true
        },
        {
            id: 9,
            email: "fernando@sdccd.edu",
            password_hash: "pass",
            first_name: "Fernando",
            last_name: "R",
            phone: "5551212",
            role_name: "Faculty",
            enabled: true
        }
    ];
}

export async function getUsers() {
    try {
        const users = await request("/users", "GET");

        if (!Array.isArray(users)) {
            throw new Error("Users response was not an array.");
        }

        return applyLocalUserStatus(users);
    } catch (error) {
        console.error("Could not load users from backend. Using local users:", error);
        return applyLocalUserStatus(getMockUsers());
    }
}

export async function updateUser(userData) {
    return request("/users", "PATCH", userData);
}

export async function disableUser(userData) {
    try {
        return request("/users", "PATCH", {
            ...userData,
            enabled: false
        });
    } catch (error) {
        console.error("Could not disable user on backend. Saving locally:", error);
    }

    const disabledUserIds = getDisabledUserIds();
    if (!disabledUserIds.includes(String(userData.id))) {
        saveDisabledUserIds([
            ...disabledUserIds,
            userData.id
        ]);
    }

    return {
        ...userData,
        enabled: false
    };
}

export async function enableUser(userData) {
    try {
        return request("/users", "PATCH", {
            ...userData,
            enabled: true
        });
    } catch (error) {
        console.error("Could not enable user on backend. Saving locally:", error);
    }

    const disabledUserIds = getDisabledUserIds();

    saveDisabledUserIds(
        disabledUserIds.filter(userId => String(userId) !== String(userData.id))
    );

    return {
        ...userData,
        enabled: true
    };
}

export async function createAttendee(attendeeData) {
    try {
        return await request("/attendees", "POST", attendeeData);
    } catch (error) {
        console.error("Could not create attendee on backend. Saving locally:", error);
    }

    return saveLocalAttendee(attendeeData);
}

export async function getAttendees() {
    try {
        const attendees = await request("/attendees", "GET");

        if (!Array.isArray(attendees)) {
            throw new Error("Attendees response was not an array.");
        }

        return mergeLocalAttendees(attendees);
    } catch (error) {
        console.error("Could not load attendees from /attendees. Trying legacy route:", error);
    }

    try {
        const attendees = await request("/attendee", "GET");

        return Array.isArray(attendees)
            ? mergeLocalAttendees(attendees)
            : getLocalAttendees();
    } catch (error) {
        console.error("Could not load attendees from backend. Using local attendees:", error);
    }

    return getLocalAttendees();
}
