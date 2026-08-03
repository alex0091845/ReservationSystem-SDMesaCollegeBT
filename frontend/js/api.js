import { validateReservationData } from "./utils/reservationValidation.js";

const API_ORIGIN = window.RESERVATION_API_ORIGIN || window.location.origin;
const BASE_URL = `${API_ORIGIN}/api`;
const REQUEST_TIMEOUT_MS = 8000;
const EVENT_CREATE_FALLBACK_CONCURRENCY = 8;
const RESERVATION_TIME_ZONE = "America/Los_Angeles";
const RESERVATION_DATE_TIME_FORMATTER = new Intl.DateTimeFormat("en-US", {
    timeZone: RESERVATION_TIME_ZONE,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23"
});
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

function normalizeReservationDraft(draft) {
    if (!draft || typeof draft !== "object" || Array.isArray(draft)) {
        return null;
    }

    const payload = typeof draft.payload === "string"
        ? parseJsonValue(draft.payload)
        : draft.payload;

    return {
        ...draft,
        payload: payload || {}
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
    const textResponse = typeof error?.data === "string"
        ? error.data.trim()
        : "";
    const responseMessage =
        error?.data?.error ||
        error?.data?.message ||
        (!isHtmlErrorResponse(textResponse) ? textResponse : "");

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

function isHtmlErrorResponse(responseText) {
    if (!responseText) {
        return false;
    }

    return /^<!doctype\s+html/i.test(responseText) ||
        /^<html[\s>]/i.test(responseText) ||
        /<title>/i.test(responseText);
}

function shouldUseLegacyEventCreationFallback(error) {
    const status = error?.status;

    return status === 404 ||
        status === 405 ||
        status === 501 ||
        status === 502 ||
        status === 503 ||
        status === 504 ||
        (status === 403 && isHtmlErrorResponse(String(error?.data || "").trim()));
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

function toRecurringEventTemplate(eventData) {
    const eventTemplate = toBackendEvent(eventData);

    delete eventTemplate.start_time;
    delete eventTemplate.end_time;

    return eventTemplate;
}

function buildRecurringCreateRequest(events) {
    if (events.length < 2) {
        return null;
    }

    const eventTemplate = toRecurringEventTemplate(events[0]);
    const recurrenceGroupId = eventTemplate.recurrence_group_id;

    if (!recurrenceGroupId) {
        return null;
    }

    const serializedTemplate = JSON.stringify(eventTemplate);
    const rangeGroups = new Map();

    for (const event of events) {
        const candidateTemplate = toRecurringEventTemplate(event);

        if (
            candidateTemplate.recurrence_group_id !== recurrenceGroupId ||
            JSON.stringify(candidateTemplate) !== serializedTemplate
        ) {
            return null;
        }

        const start = new Date(event.start_time ?? event.start);
        const end = new Date(event.end_time ?? event.end);

        if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime())) {
            return null;
        }

        const startParts = getReservationDateTimeParts(start);
        const durationMilliseconds = end.getTime() - start.getTime();
        const groupKey = [
            getReservationWeekday(startParts),
            startParts.hour,
            startParts.minute,
            durationMilliseconds
        ].join("|");

        if (!rangeGroups.has(groupKey)) {
            rangeGroups.set(groupKey, []);
        }

        rangeGroups.get(groupKey).push({
            event,
            start,
            end,
            startParts
        });
    }

    const ranges = [];

    for (const groupedEvents of rangeGroups.values()) {
        groupedEvents.sort((first, second) => first.start - second.start);

        const baseOccurrence = groupedEvents[0];
        const baseDateNumber = getReservationDateNumber(baseOccurrence.startParts);
        const weekOffsets = [];

        for (const occurrence of groupedEvents) {
            const dayDifference =
                getReservationDateNumber(occurrence.startParts) - baseDateNumber;

            if (dayDifference < 0 || dayDifference % 7 !== 0) {
                return null;
            }

            weekOffsets.push(dayDifference / 7);
        }

        const weekCount = weekOffsets[weekOffsets.length - 1] + 1;
        const includedWeekOffsets = new Set(weekOffsets);
        const excludedWeekOffsets = Array.from(
            { length: weekCount },
            (_, weekOffset) => weekOffset
        ).filter(weekOffset => !includedWeekOffsets.has(weekOffset));

        ranges.push({
            start_time: baseOccurrence.start.toISOString(),
            end_time: baseOccurrence.end.toISOString(),
            week_count: weekCount,
            excluded_week_offsets: excludedWeekOffsets
        });
    }

    return {
        event: eventTemplate,
        ranges
    };
}

function getReservationDateTimeParts(date) {
    return Object.fromEntries(
        RESERVATION_DATE_TIME_FORMATTER.formatToParts(date)
            .filter(part => part.type !== "literal")
            .map(part => [part.type, Number(part.value)])
    );
}

function getReservationDateNumber(parts) {
    return Date.UTC(parts.year, parts.month - 1, parts.day) / 86400000;
}

function getReservationWeekday(parts) {
    return new Date(Date.UTC(parts.year, parts.month - 1, parts.day)).getUTCDay();
}

function parseJsonValue(value) {
    try {
        return JSON.parse(value);
    } catch (error) {
        return null;
    }
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

export async function createEvents(eventsToCreate, {
    existingReservations = null,
    users = null
} = {}) {
    const eventList = Array.isArray(eventsToCreate)
        ? eventsToCreate.filter(Boolean)
        : [];

    if (eventList.length === 0) {
        return [];
    }

    const validationUsers = users || await getValidationUsers();
    const existingEvents = existingReservations || await getEvents();
    const validatedEvents = [];

    for (const eventData of eventList) {
        const validation = validateReservationData({
            reservationData: eventData,
            existingReservations: [
                ...existingEvents,
                ...validatedEvents
            ],
            users: validationUsers
        });

        if (!validation.isValid) {
            throw new Error(validation.message);
        }

        const hostUser = getEventHostById(eventData.host_user_id, validationUsers);
        const eventForBackend = {
            ...eventData,
            host_user_id: hostUser?.id ?? eventData.host_user_id
        };

        validatedEvents.push(eventForBackend);
    }

    try {
        const recurringRequest = buildRecurringCreateRequest(validatedEvents);
        let createResponse;

        if (recurringRequest) {
            try {
                createResponse = await request(
                    "/events/recurring",
                    "POST",
                    recurringRequest
                );
            } catch (error) {
                if (!shouldUseLegacyEventCreationFallback(error)) {
                    throw error;
                }

                console.warn(
                    "Compact recurring reservation endpoint unavailable; retrying with the batch endpoint.",
                    error
                );
            }
        }

        if (!createResponse) {
            createResponse = await request(
                "/events/batch",
                "POST",
                validatedEvents.map(toBackendEvent)
            );
        }

        const createdEvents = normalizeCollection(
            createResponse,
            ["events", "data", "items", "records"]
        ).map(normalizeEvent).filter(Boolean);

        return validatedEvents.map((eventForBackend, index) => {
            const createdEvent = createdEvents[index] || {};

            return attachHostUser({
                ...eventForBackend,
                ...createdEvent,
                id: createdEvent.id ?? eventForBackend.id
            }, validationUsers);
        });
    } catch (error) {
        if (shouldUseLegacyEventCreationFallback(error)) {
            console.warn(
                "Batch reservation creation failed; retrying with individual reservation requests.",
                error
            );

            try {
                return await createEventsIndividually(
                    validatedEvents,
                    validationUsers
                );
            } catch (fallbackError) {
                console.error("Could not create events with fallback requests:", fallbackError);
                throw new Error(
                    getRequestFailureMessage(
                        fallbackError,
                        "Could not save the reservations to the backend."
                    )
                );
            }
        }

        console.error("Could not create events on backend:", error);
        throw new Error(
            getRequestFailureMessage(
                error,
                "Could not save the reservations to the backend."
            )
        );
    }
}

async function createEventsIndividually(validatedEvents, validationUsers) {
    const createdEvents = [];

    for (
        let index = 0;
        index < validatedEvents.length;
        index += EVENT_CREATE_FALLBACK_CONCURRENCY
    ) {
        const chunk = validatedEvents.slice(
            index,
            index + EVENT_CREATE_FALLBACK_CONCURRENCY
        );
        const chunkResults = await Promise.allSettled(chunk.map(eventForBackend => {
            return createValidatedEvent(eventForBackend, validationUsers);
        }));

        for (const chunkResult of chunkResults) {
            if (chunkResult.status === "fulfilled") {
                createdEvents.push(chunkResult.value);
            }
        }

        const failedResult = chunkResults.find(chunkResult => {
            return chunkResult.status === "rejected";
        });

        if (failedResult) {
            const abandonedEvents = await rollBackCreatedEvents(createdEvents);

            if (abandonedEvents.length > 0) {
                throw buildAbandonedReservationsError(abandonedEvents);
            }

            throw failedResult.reason;
        }
    }

    return createdEvents;
}

function toReservationId(value) {
    if (value === null || value === undefined || value === "") {
        return null;
    }

    const numberValue = Number(value);

    return Number.isInteger(numberValue) && numberValue > 0 ? numberValue : null;
}

async function rollBackCreatedEvents(createdEvents) {
    const abandonedEvents = [];

    for (const createdEvent of createdEvents) {
        const eventId = toReservationId(createdEvent?.id);

        if (eventId === null) {
            abandonedEvents.push(createdEvent);
            continue;
        }

        try {
            await deleteEvent({ id: eventId });
        } catch (error) {
            console.error(
                "Could not roll back reservation after a failed batch:",
                eventId,
                error
            );
            abandonedEvents.push(createdEvent);
        }
    }

    return abandonedEvents;
}

function buildAbandonedReservationsError(abandonedEvents) {
    const identifiers = abandonedEvents
        .map(abandonedEvent => toReservationId(abandonedEvent?.id))
        .filter(eventId => eventId !== null);
    const identifierSummary = identifiers.length > 0
        ? ` (reservation ${identifiers.length === 1 ? "ID" : "IDs"}: ${identifiers.join(", ")})`
        : "";
    const error = new Error(
        `Only part of this booking could be saved, and ${abandonedEvents.length} ` +
        `${abandonedEvents.length === 1 ? "reservation" : "reservations"} could not be ` +
        `removed automatically${identifierSummary}. Please delete ` +
        `${abandonedEvents.length === 1 ? "it" : "them"} before trying again.`
    );

    error.data = { error: error.message };

    return error;
}

async function createValidatedEvent(eventForBackend, validationUsers) {
    const createResponse = await request(
        "/events",
        "POST",
        toBackendEvent(eventForBackend)
    );
    const createdEvent = normalizeCollection(
        createResponse,
        ["events", "data", "items", "records"]
    ).map(normalizeEvent).filter(Boolean)[0] ||
        normalizeEvent(createResponse);

    return attachHostUser({
        ...eventForBackend,
        ...createdEvent,
        id: createdEvent?.id ?? eventForBackend.id
    }, validationUsers);
}

export async function deleteEvent(eventData) {
    return request(`/events/${eventData.id}`, "DELETE");
}

export async function getReservationDraft({
    draft_type,
    source_event_id = null
}) {
    const params = new URLSearchParams({
        draft_type
    });

    if (source_event_id !== undefined && source_event_id !== null && source_event_id !== "") {
        params.set("source_event_id", source_event_id);
    }

    const drafts = normalizeCollection(
        await request(`/reservation-drafts?${params.toString()}`, "GET"),
        ["reservation_drafts", "drafts", "data", "items", "records"]
    ).map(normalizeReservationDraft).filter(Boolean);

    return drafts[0] || null;
}

export async function saveReservationDraft(draftData) {
    const saveResponse = await request("/reservation-drafts", "POST", draftData);
    const savedDraft = normalizeCollection(
        saveResponse,
        ["reservation_drafts", "drafts", "data", "items", "records"]
    ).map(normalizeReservationDraft).filter(Boolean)[0] ||
        normalizeReservationDraft(saveResponse);

    return savedDraft;
}

export async function deleteReservationDraft(draftData) {
    if (!draftData?.id) {
        return null;
    }

    return request(`/reservation-drafts/${draftData.id}`, "DELETE");
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
