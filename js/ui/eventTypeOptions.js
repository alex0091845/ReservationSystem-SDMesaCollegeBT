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

export function renderEventTypeOptions(
    selectElement,
    eventTypes,
    { selectedValue = selectElement?.value || "", placeholder = "Select event type" } = {}
) {
    if (!selectElement) {
        return;
    }

    const normalizedEventTypes = Array.isArray(eventTypes)
        ? eventTypes.filter(eventType => eventType?.value)
        : [];
    const normalizedSelectedValue = normalizeEventTypeValue(selectedValue);
    const placeholderOption = new Option(placeholder, "");

    placeholderOption.disabled = true;
    placeholderOption.selected = !normalizedSelectedValue;

    selectElement.replaceChildren(placeholderOption);

    normalizedEventTypes.forEach(eventType => {
        selectElement.appendChild(
            new Option(eventType.label || eventType.value, eventType.value)
        );
    });

    if (
        normalizedSelectedValue &&
        !normalizedEventTypes.some(eventType => {
            return String(eventType.value) === String(normalizedSelectedValue);
        })
    ) {
        selectElement.appendChild(
            new Option(
                formatEventTypeLabel(normalizedSelectedValue),
                normalizedSelectedValue
            )
        );
    }

    selectElement.value = normalizedSelectedValue || "";
    selectElement.disabled = normalizedEventTypes.length === 0 && !normalizedSelectedValue;
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
