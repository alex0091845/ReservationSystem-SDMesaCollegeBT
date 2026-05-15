const BASE_URL = "http://18.223.249.15:8080/api";

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
    return [{
        "id": 1,
        "host_user": {
            "id": 6,
            "email": "1234@gmail.com",
            "first_name": "Alex",
            "last_name": "Chow",
            "phone": "5551212",
            "role_name": "Faculty"
        },
        "start_time": new Date(),
        "end_time": new Date(),
        "event_type": "Study",
        "description": "The quick brown fox jumps over the lazy dog.",
        "title": "Team Meeting",
        "department": "Computer Science",
        "is_public": true},
    {
        "id": 2,
        "host_user": {
            "id": 6,
            "email": "1234@gmail.com",
            "first_name": "Alex",
            "last_name": "Chow",
            "phone": "5551212",
            "role_name": "Faculty"
        },
        "start_time": new Date("2026-05-16T10:00:00"),
        "end_time": new Date("2026-05-16T11:00:00"),
        "event_type": "Meeting",
        "description": "The quick brown fox jumps over the lazy dog.",
        "title": "Work",
        "department": "Computer Science",
        "is_public": true}
    ];
}

export function createEvent(eventData) {
    console.log(eventData);
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