import { validateReservationData } from "./utils/reservationValidation.js";

const API_ORIGIN = window.RESERVATION_API_ORIGIN || window.location.origin;
const BASE_URL = `${API_ORIGIN}/api`;
const REQUEST_TIMEOUT_MS = 8000;
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

export async function isUserDisabled(userId) {
    try {
        const currentUser = await getCurrentSession();

        if (currentUser && String(currentUser.id) === String(userId)) {
            return currentUser.enabled === false;
        }
    } catch (error) {
        // No active backend session. Fall through to the users list.
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

    return false;
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
        is_public: eventData.is_public,
        recurrence_group_id: eventData.recurrence_group_id
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
    const events = normalizeCollection(
        await request("/events", "GET"),
        ["events", "data", "items", "records"]
    ).map(normalizeEvent).filter(Boolean);

    return events.map(event => attachHostUser(event));
}

export async function getEventTypes() {
    return normalizeCollection(
        await request("/event-types", "GET"),
        ["event_types", "data", "items", "records"]
    ).map(normalizeEventType).filter(Boolean);
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

        return attachHostUser({
            ...eventForBackend,
            ...createdEvent,
            id: createdEvent?.id ?? eventForBackend.id
        }, users);
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

    const updateResponse = await request(`/events/${eventData.id}`, "PATCH", toBackendEvent(eventData));
    const updatedEvent = normalizeCollection(
        updateResponse,
        ["events", "data", "items", "records"]
    ).map(normalizeEvent).filter(Boolean)[0] ||
        normalizeEvent(updateResponse);

    return attachHostUser(updatedEvent || eventData, users);
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

export async function getUsers() {
    const users = await request("/users", "GET");

    if (!Array.isArray(users)) {
        throw new Error("Users response was not an array.");
    }

    return users.map(normalizeUser);
}

export async function updateUser(userData) {
    return request(`/users/${userData.id}`, "PATCH", userData);
}

export async function disableUser(userData) {
    return request(`/users/${userData.id}`, "PATCH", {
        ...userData,
        enabled: false
    });
}

export async function enableUser(userData) {
    return request(`/users/${userData.id}`, "PATCH", {
        ...userData,
        enabled: true
    });
}

export async function createAttendee(attendeeData) {
    return normalizeAttendee(await request("/attendees", "POST", attendeeData));
}

export async function getAttendees() {
    return normalizeCollection(
        await request("/attendees", "GET"),
        ["attendees", "data", "items", "records"]
    ).map(normalizeAttendee);
}
