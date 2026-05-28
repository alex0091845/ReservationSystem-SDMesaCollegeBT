const REQUIRED_RESERVATION_FIELDS = [
    ["host_user_id", "Host user"],
    ["start_time", "Start time"],
    ["end_time", "End time"],
    ["event_type", "Event type"],
    ["description", "Description"],
    ["title", "Event title"],
    ["department", "Department"],
    ["is_public", "Event access"]
];
const ROOM_FIELD_NAMES = ["room_id", "roomId", "room_name", "room", "location"];

export function validateReservationData({
    reservationData,
    existingReservations = [],
    users = [],
    requireId = false
}) {
    const validationMessage = getReservationValidationMessage({
        reservationData,
        existingReservations,
        users,
        requireId
    });

    return {
        isValid: validationMessage === "",
        message: validationMessage
    };
}

function getReservationValidationMessage({
    reservationData,
    existingReservations,
    users,
    requireId
}) {
    if (!reservationData) {
        return "Reservation data is required before saving.";
    }

    if (requireId && isBlankValue(reservationData.id)) {
        return "Reservation id is required before updating this reservation.";
    }

    const missingField = REQUIRED_RESERVATION_FIELDS.find(([fieldName]) => {
        return isBlankValue(reservationData[fieldName]);
    });

    if (missingField) {
        return `${missingField[1]} is required before saving this reservation.`;
    }

    const startDate = parseReservationDate(reservationData.start_time);
    const endDate = parseReservationDate(reservationData.end_time);

    if (!startDate) {
        return "Start time must be a valid date and time.";
    }

    if (!endDate) {
        return "End time must be a valid date and time.";
    }

    if (endDate <= startDate) {
        return "End time must be after start time.";
    }

    const hostUser = getReservationHostUser(reservationData, users);

    if (hostUser?.enabled === false) {
        return "Disabled users cannot create or edit reservations.";
    }

    const overlappingReservation = getOverlappingReservation({
        reservationData,
        existingReservations,
        startDate,
        endDate
    });

    if (overlappingReservation) {
        return `This reservation overlaps with "${overlappingReservation.title || "Untitled reservation"}" (${formatReservationRange(overlappingReservation)}).`;
    }

    return "";
}

function getOverlappingReservation({
    reservationData,
    existingReservations,
    startDate,
    endDate
}) {
    return existingReservations.find(existingReservation => {
        if (isSameReservation(reservationData, existingReservation)) {
            return false;
        }

        if (!isSameReservationRoom(reservationData, existingReservation)) {
            return false;
        }

        const existingStartDate = parseReservationDate(existingReservation.start_time);
        const existingEndDate = parseReservationDate(existingReservation.end_time);

        if (!existingStartDate || !existingEndDate) {
            return false;
        }

        return startDate < existingEndDate && endDate > existingStartDate;
    });
}

function isSameReservation(reservationData, existingReservation) {
    if (
        isBlankValue(reservationData.id) ||
        isBlankValue(existingReservation.id)
    ) {
        return false;
    }

    return String(reservationData.id) === String(existingReservation.id);
}

function isSameReservationRoom(reservationData, existingReservation) {
    const reservationRoom = getReservationRoomValue(reservationData);
    const existingRoom = getReservationRoomValue(existingReservation);

    if (reservationRoom === null || existingRoom === null) {
        return true;
    }

    return String(reservationRoom).toLowerCase() === String(existingRoom).toLowerCase();
}

function getReservationRoomValue(reservation) {
    const roomFieldName = ROOM_FIELD_NAMES.find(fieldName => {
        return !isBlankValue(reservation[fieldName]);
    });

    return roomFieldName ? reservation[roomFieldName] : null;
}

function getReservationHostUser(reservationData, users) {
    const hostUserId =
        reservationData.host_user_id ??
        reservationData.host_user?.id;

    if (hostUserId === undefined || hostUserId === null) {
        return reservationData.host_user || null;
    }

    return users.find(user => {
        return String(user.id) === String(hostUserId);
    }) || reservationData.host_user || null;
}

function isBlankValue(value) {
    if (value === false || value === 0) {
        return false;
    }

    return value === undefined ||
        value === null ||
        String(value).trim() === "";
}

function parseReservationDate(value) {
    if (isBlankValue(value)) {
        return null;
    }

    const date = new Date(value);

    return Number.isNaN(date.getTime()) ? null : date;
}

function formatReservationRange(reservation) {
    const startDate = parseReservationDate(reservation.start_time);
    const endDate = parseReservationDate(reservation.end_time);

    if (!startDate || !endDate) {
        return "time unavailable";
    }

    const date = startDate.toLocaleDateString([], {
        month: "short",
        day: "numeric",
        year: "numeric"
    });

    const startTime = startDate.toLocaleTimeString([], {
        hour: "numeric",
        minute: "2-digit"
    });

    const endTime = endDate.toLocaleTimeString([], {
        hour: "numeric",
        minute: "2-digit"
    });

    return `${date}, ${startTime} - ${endTime}`;
}
