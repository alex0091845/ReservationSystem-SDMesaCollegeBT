const BASE_URL = "http://18.223.249.15:8080/api";
const DISABLED_USER_IDS_STORAGE_KEY = "disabledUserIds";
const EVENT_OVERRIDES_STORAGE_KEY = "eventOverrides";

function getDisabledUserIds() {
    try {
        if (typeof localStorage === "undefined") {
            return [];
        }

        const storedValue = localStorage.getItem(DISABLED_USER_IDS_STORAGE_KEY);
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
    if (typeof localStorage === "undefined") {
        return;
    }

    localStorage.setItem(
        DISABLED_USER_IDS_STORAGE_KEY,
        JSON.stringify([...new Set(userIds.map(String))])
    );
}

export function isUserDisabled(userId) {
    return getDisabledUserIds().includes(String(userId));
}

function getEventOverrides() {
    try {
        if (typeof localStorage === "undefined") {
            return [];
        }

        const storedValue = localStorage.getItem(EVENT_OVERRIDES_STORAGE_KEY);
        const parsedValue = JSON.parse(storedValue || "[]");

        return Array.isArray(parsedValue) ? parsedValue : [];
    } catch (error) {
        console.error("Could not read event overrides:", error);
        return [];
    }
}

function saveEventOverride(eventData) {
    if (typeof localStorage === "undefined") {
        return eventData;
    }

    const eventOverrides = getEventOverrides();
    const updatedEventOverrides = [
        ...eventOverrides.filter(event => String(event.id) !== String(eventData.id)),
        eventData
    ];

    localStorage.setItem(
        EVENT_OVERRIDES_STORAGE_KEY,
        JSON.stringify(updatedEventOverrides)
    );

    return eventData;
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
        role_name: user.role_name
    };
}

function getBaseEvents() {
    const alexChow = {
        "id": 1,
        "email": "alex.chow@college.edu",
        "first_name": "Alex",
        "last_name": "Chow",
        "phone": "5551212",
        "role_name": "Faculty"
    };


    return [{
        "id": 1,
        "host_user_id": alexChow.id,
        "host_user": alexChow,
        "start_time": new Date(),
        "end_time": new Date(),
        "event_type": "Study",
        "description": "The quick brown fox jumps over the lazy dog.",
        "title": "Team Meeting",
        "department": "Computer Science",
        "is_public": true
    },
    {
        "id": 2,
        "host_user_id": alexChow.id,
        "host_user": alexChow,
        "start_time": new Date("2026-05-16T10:00:00"),
        "end_time": new Date("2026-05-16T11:00:00"),
        "event_type": "Work",
        "description": "The quick brown fox jumps over the lazy dog.",
        "title": "Work",
        "department": "Computer Science",
        "is_public": true
    }
    ];
}

// cookie-cutter request helper called by all data functions
async function request(endpoint, method = "GET", data = null) {
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 2000);

    const options = {
        method,
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

        if (!response.ok) {
            throw new Error(`Request failed: ${method} ${endpoint}`);
        }

        return response.json();
    } finally {
        clearTimeout(timeoutId);
    }
}

export function getEvents() {
    // return request("/events", "GET");
    const events = getBaseEvents();
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

export function createEvent(eventData) {
    if (isUserDisabled(eventData.host_user_id)) {
        throw new Error("Disabled users cannot create reservations.");
    }

    const events = getEvents();
    const hostUser = getEventHostUser(getUsers().find(
        user => String(user.id) === String(eventData.host_user_id)
    ));

    const createdEvent = {
        ...eventData,
        id: getNextEventId(events),
        host_user_id: hostUser?.id ?? eventData.host_user_id,
        host_user: hostUser || {
            id: eventData.host_user_id
        }
    };

    // return request("/events", "POST", createdEvent);
    return saveEventOverride(createdEvent);
}

export function deleteEvent(eventData) {
    return request("/events", "DELETE", eventData);
}

export function updateEvent(eventData) {
    // return request("/events", "PATCH", eventData);
    return saveEventOverride(eventData);
}

export function createUser(userData) {
    return request("/users", "POST", userData);
}

// NOT DOCUMENTED
export function getUsers() {
    // return request("/users", "GET");
    const testUsers = [
        {
            id: 1,
            email: "alex@sdccd.edu",
            password_hash: "pass",
            first_name: "Alex",
            last_name: "Chow",
            phone: "5551212",
            role_name: "Faculty"
        },
        {
            id: 2,
            email: "leo@sdccd.edu",
            password_hash: "pass",
            first_name: "Leo",
            last_name: "Nguyen",
            phone: "5551212",
            role_name: "Faculty"
        },
        {
            id: 3,
            email: "jordan@sdccd.edu",
            password_hash: "pass",
            first_name: "Jordan",
            last_name: "Ayling",
            phone: "5551212",
            role_name: "Admin"
        },
        {
            id: 4,
            email: "admin",
            password_hash: "admin",
            first_name: "admin",
            last_name: "admin",
            phone: "5551212",
            role_name: "Admin"
        },
        {
            id: 5,
            email: "allan@sdccd.edu",
            password_hash: "pass",
            first_name: "Allan",
            last_name: "Schougaard",
            phone: "5551212",
            role_name: "Faculty"
        },
        {
            id: 6,
            email: "braulio@sdccd.edu",
            password_hash: "pass",
            first_name: "Braulio",
            last_name: "Ochoa",
            phone: "5551212",
            role_name: "Faculty"
        },
        {
            id: 7,
            email: "dominic@sdccd.edu",
            password_hash: "pass",
            first_name: "Dominic",
            last_name: "Last Name",
            phone: "5551212",
            role_name: "Faculty"
        },
        {
            id: 8,
            email: "nathan@sdccd.edu",
            password_hash: "pass",
            first_name: "Nathan",
            last_name: "Last Name",
            phone: "5551212",
            role_name: "Faculty"
        },
        {
            id: 9,
            email: "fernando@sdccd.edu",
            password_hash: "pass",
            first_name: "Fernando",
            last_name: "R",
            phone: "5551212",
            role_name: "Faculty"
        }
    ];

    const disabledUserIds = getDisabledUserIds();

    return testUsers.map(user => ({
        ...user,
        disabled: disabledUserIds.includes(String(user.id))
    }));
}

export function updateUser(userData) {
    return request("/users", "PATCH", userData);
}

export function disableUser(userData) {
    const disabledUserIds = getDisabledUserIds();

    if (!disabledUserIds.includes(String(userData.id))) {
        saveDisabledUserIds([
            ...disabledUserIds,
            userData.id
        ]);
    }

    // return request("/users", "PATCH", { ...userData, disabled: true });
    return {
        ...userData,
        disabled: true
    };
}

export function enableUser(userData) {
    const disabledUserIds = getDisabledUserIds();

    saveDisabledUserIds(
        disabledUserIds.filter(userId => String(userId) !== String(userData.id))
    );

    // return request("/users", "PATCH", { ...userData, disabled: false });
    return {
        ...userData,
        disabled: false
    };
}
// END NOT DOCUMENTED

export function createAttendee(attendeeData) {
    return request("/attendees", "POST", attendeeData);
}

export function getAttendees() {
    return request("/attendees", "GET");
}
