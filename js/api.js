const BASE_URL = "http://18.223.249.15:8080/api";

// cookie-cutter request helper called by all data functions
async function request(endpoint, method = "GET", data = null) {
    const options = {
        method,
        headers: {
            "Content-Type": "application/json"
        }
    };

    if (data && method !== "GET") {
        options.body = JSON.stringify(data);
    }

    const response = await fetch(`${BASE_URL}${endpoint}`, options);

    if (!response.ok) {
        throw new Error(`Request failed: ${method} ${endpoint}`);
    }

    return response.json();
}

export function getEvents() {
    return request("/events", "GET");
}

export function createEvent(eventData) {
    return request("/events", "POST", eventData);
}

export function deleteEvent(eventData) {
    return request("/events", "DELETE", eventData);
}

export function updateEvent(eventData) {
    return request("/events", "PATCH", eventData);
}

export function createUser(userData) {
    return request("/users", "POST", userData);
}

export function createAttendee(attendeeData) {
    return request("/attendees", "POST", attendeeData);
}

export function getAttendees() {
    return request("/attendees", "GET");
}