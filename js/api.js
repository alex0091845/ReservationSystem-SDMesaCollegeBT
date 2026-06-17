import { validateReservationData } from "./utils/reservationValidation.js";

const API_ORIGIN = window.RESERVATION_API_ORIGIN || window.location.origin;
const BASE_URL = `${API_ORIGIN}/api`;
const REQUEST_TIMEOUT_MS = 8000;
const DISABLED_USER_IDS_STORAGE_KEY = "disabledUserIds";
const EVENT_OVERRIDES_STORAGE_KEY = "eventOverrides";
const ATTENDEE_OVERRIDES_STORAGE_KEY = "attendeeOverrides";
const FALLBACK_EVENT_TYPES = [
    { value: "Meeting", label: "Meeting", description: "Social" },
    { value: "Other", label: "Other", description: "Hands-on learning session" },
    { value: "Social", label: "Social", description: "Social event" },
    { value: "Study_Group", label: "Study Group", description: "Collaborative student study" },
    { value: "Workshop", label: "Workshop", description: "Educational workshop events" }
];
const EVENT_TYPE_VALUE_ALIASES = {
    meeting: "Meeting",
    office_hours: "Other",
    other: "Other",
    social: "Social",
    study: "Study_Group",
    study_group: "Study_Group",
    work: "Workshop",
    workshop: "Workshop"
};

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
    try {
        const currentUser = await getCurrentSession();

        if (currentUser && String(currentUser.id) === String(userId)) {
            return currentUser.enabled === false;
        }
    } catch (error) {
        // No active backend session. Fall through to local/offline status.
    }

    try {
        const user = (await getUsers()).find(testUser => {
            return String(testUser.id) === String(userId);
        });

        if (user) {
            return user.enabled === false;
        }
    } catch (error) {
        console.error("Could not check user status from backend:", error);
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

        return Array.isArray(parsedValue)
            ? parsedValue.map(normalizeEvent).filter(hasRenderableEventDates)
            : [];
    } catch (error) {
        console.error("Could not read event overrides:", error);
        return [];
    }
}

function hasRenderableEventDates(event) {
    if (!event?.start_time || !event?.end_time) {
        return false;
    }

    return !Number.isNaN(new Date(event.start_time).getTime()) &&
        !Number.isNaN(new Date(event.end_time).getTime());
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
        role_name: getUserRoleName(user),
        enabled: user.enabled !== false
    };
}

function getEventHostById(hostUserId, users = []) {
    return getEventHostUser(users.find(user => {
        return String(user.id) === String(hostUserId);
    }));
}

function attachHostUser(event, users = []) {
    const hostUser = getEventHostById(event.host_user_id, users) ||
        getEventHostUser(event.host_user);

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
        "event_type": "Meeting",
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
        "event_type": "Workshop",
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
        "event_type": "Workshop",
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
        ...normalizeUser(user),
        enabled: disabledUserIds.includes(String(user.id))
            ? false
            : user.enabled !== false
    }));
}

function normalizeUser(user) {
    if (!user) {
        return user;
    }

    return {
        ...user,
        role_name: getUserRoleName(user)
    };
}

function getUserRoleName(user) {
    return user.role_name || user.role || user.user_roles?.name || "";
}

function normalizeEvent(event) {
    if (!event || typeof event !== "object" || Array.isArray(event)) {
        return null;
    }

    const hostUserId = event.host_user_id ?? event.user_id;
    const hostUser = event.host_user || event.users;

    return {
        ...event,
        host_user_id: hostUserId,
        start_time: event.start_time ?? event.start,
        end_time: event.end_time ?? event.end,
        event_type: normalizeEventTypeValue(event.event_type),
        host_user: hostUser
            ? {
                ...hostUser,
                id: hostUser.id ?? hostUserId
            }
            : event.host_user
    };
}

function normalizeAttendee(attendee) {
    if (!attendee) {
        return attendee;
    }

    const [firstName = "", ...lastNameParts] =
        attendee.full_name && !attendee.first_name
            ? attendee.full_name.split(/\s+/)
            : [];

    return {
        ...attendee,
        first_name: attendee.first_name ?? firstName,
        last_name: attendee.last_name ?? lastNameParts.join(" "),
        email: attendee.email ?? attendee.student_email,
        check_in_time: attendee.check_in_time ?? attendee.check_in ?? attendee.checked_in
    };
}

function normalizeEventType(eventType) {
    const rawValue = typeof eventType === "string"
        ? eventType
        : eventType?.event_type ?? eventType?.name ?? eventType?.type ?? eventType?.value;
    const value = normalizeEventTypeValue(rawValue);

    if (!value) {
        return null;
    }

    return {
        value: String(value),
        label: eventType?.label || formatEventTypeLabel(value),
        description: eventType?.description || ""
    };
}

function formatEventTypeLabel(value) {
    return String(value)
        .replace(/[_-]+/g, " ")
        .replace(/\s+/g, " ")
        .trim()
        .replace(/\b\w/g, letter => letter.toUpperCase());
}

function getFallbackEventTypes() {
    return FALLBACK_EVENT_TYPES.map(eventType => ({ ...eventType }));
}

function normalizeEventTypeValue(value) {
    if (value === undefined || value === null) {
        return value;
    }

    const rawValue = String(value).trim();

    if (!rawValue) {
        return rawValue;
    }

    const aliasKey = rawValue.toLowerCase().replace(/[-\s]+/g, "_");

    return EVENT_TYPE_VALUE_ALIASES[aliasKey] || rawValue;
}

function normalizeCollection(responseData, collectionKeys = []) {
    if (Array.isArray(responseData)) {
        return responseData;
    }

    for (const collectionKey of collectionKeys) {
        if (Array.isArray(responseData?.[collectionKey])) {
            return responseData[collectionKey];
        }
    }

    return [];
}

function getRequestFailureMessage(error, fallbackMessage) {
    const responseMessage =
        error?.data?.error ||
        error?.data?.message ||
        (typeof error?.data === "string" ? error.data : "");

    if (responseMessage) {
        return responseMessage;
    }

    if (error?.status === 401) {
        return "Your login session could not be verified. Please sign in again.";
    }

    if (error?.status === 403) {
        return "You do not have permission to complete this request.";
    }

    return fallbackMessage;
}

function toBackendEvent(eventData) {
    return {
        host_user_id: normalizeInteger(
            eventData.host_user_id ?? eventData.user_id
        ),
        start_time: eventData.start_time ?? eventData.start,
        end_time: eventData.end_time ?? eventData.end,
        event_type: normalizeEventTypeValue(eventData.event_type),
        description: eventData.description,
        title: eventData.title,
        department: eventData.department,
        is_public: eventData.is_public
    };
}

function normalizeInteger(value) {
    const numberValue = Number(value);

    return Number.isNaN(numberValue) ? value : numberValue;
}

async function getValidationUsers() {
    try {
        const currentUser = await getCurrentSession();

        if (getUserRoleName(currentUser).toLowerCase() === "admin") {
            return getUsers();
        }

        return currentUser ? [currentUser] : [];
    } catch (error) {
        return [];
    }
}

// cookie-cutter request helper called by all data functions
async function request(endpoint, method = "GET", data = null) {
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);

    const options = {
        method,
        credentials: "include",
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
        const responseText = response.status === 204
            ? ""
            : await response.text();
        let responseData = null;

        if (responseText) {
            try {
                responseData = JSON.parse(responseText);
            } catch (error) {
                responseData = responseText;
            }
        }

        if (!response.ok) {
            const error = new Error(`Request failed: ${method} ${endpoint}`);
            error.status = response.status;
            error.data = responseData;
            throw error;
        }

        return responseData;
    } finally {
        clearTimeout(timeoutId);
    }
}

export async function getEvents() {
    let events;

    try {
        events = normalizeCollection(
            await request("/events", "GET"),
            ["events", "data", "items", "records"]
        ).map(normalizeEvent).filter(Boolean);
    } catch (error) {
        console.error("Could not load events from backend. Using local events:", error);
        events = getBaseEvents();
    }

    return applyLocalEventOverrides(events)
        .map(event => attachHostUser(event));
}

export async function getEventTypes() {
    try {
        const eventTypes = normalizeCollection(
            await request("/event-types", "GET"),
            ["event_types", "data", "items", "records"]
        ).map(normalizeEventType).filter(Boolean);

        return eventTypes.length > 0 ? eventTypes : getFallbackEventTypes();
    } catch (error) {
        console.error("Could not load event types from backend:", error);
        return getFallbackEventTypes();
    }
}

export async function createEvent(eventData) {
    const users = await getValidationUsers();
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
        const createResponse = await request("/events", "POST", toBackendEvent(eventForBackend));
        const createdEvent = normalizeCollection(
            createResponse,
            ["events", "data", "items", "records"]
        ).map(normalizeEvent).filter(Boolean)[0] ||
            normalizeEvent(createResponse);
        const reservationToReturn = attachHostUser({
            ...eventForBackend,
            ...createdEvent,
            id: createdEvent?.id ?? eventForBackend.id ?? getNextEventId(events)
        }, users);

        return saveEventOverride(reservationToReturn);
    } catch (error) {
        console.error("Could not create event on backend:", error);
        throw new Error(
            getRequestFailureMessage(
                error,
                "Could not save the reservation to the backend."
            )
        );
    }
}

export async function deleteEvent(eventData) {
    return request(`/events/${eventData.id}`, "DELETE");
}

export async function updateEvent(eventData) {
    const users = await getValidationUsers();
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
        const updateResponse = await request(`/events/${eventData.id}`, "PATCH", toBackendEvent(eventData));
        const updatedEvent = normalizeCollection(
            updateResponse,
            ["events", "data", "items", "records"]
        ).map(normalizeEvent).filter(Boolean)[0] ||
            normalizeEvent(updateResponse);

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
    return normalizeUser(await request("/login", "POST", {
        email,
        password
    }));
}

export async function logoutUser() {
    return request("/logout", "POST");
}

export async function getCurrentSession() {
    return normalizeUser(await request("/session", "GET"));
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
    return request(`/users/${userData.id}`, "PATCH", userData);
}

export async function disableUser(userData) {
    try {
        return request(`/users/${userData.id}`, "PATCH", {
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
        return request(`/users/${userData.id}`, "PATCH", {
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
        return normalizeAttendee(await request("/attendees", "POST", attendeeData));
    } catch (error) {
        console.error("Could not create attendee on backend. Saving locally:", error);
    }

    return saveLocalAttendee(attendeeData);
}

export async function getAttendees() {
    try {
        const attendees = normalizeCollection(
            await request("/attendees", "GET"),
            ["attendees", "data", "items", "records"]
        ).map(normalizeAttendee);

        return mergeLocalAttendees(attendees);
    } catch (error) {
        console.error("Could not load attendees from backend. Using local attendees:", error);
    }

    return getLocalAttendees();
}
