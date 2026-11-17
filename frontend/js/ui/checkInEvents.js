import { formatEventTime } from "./eventCards.js";
import { getEventColorClass } from "../utils/dateUtils.js";

// How often the widget re-checks the clock. It only compares "now" with the
// events that are already loaded, so it makes no network calls.
const CLOCK_CHECK_MS = 60 * 1000;

// The First Name field of the check-in form in the event window (index.html).
const CHECK_IN_FIRST_NAME_ID = "checkInFirstName";

// The options from the newest draw in main.js. The timer reads them, so it
// always works with the same events and elements as the page.
let latestOptions = null;

// The event on screen right now (null = the empty message).
let shownEvent = null;

let clockTimer = null;

export function renderCheckInEvents(options) {
    latestOptions = options;

    drawWidget(findEventInProgress(options.reservedEvents, new Date()));

    if (clockTimer === null) {
        clockTimer = setInterval(checkClock, CLOCK_CHECK_MS);
    }
}

function checkClock() {
    const { container, reservedEvents } = latestOptions;

    // Nothing to update while the widget is hidden (for example, while a
    // faculty member is signed in).
    if (!container.isConnected || container.closest("[hidden]")) {
        return;
    }

    const eventInProgress = findEventInProgress(reservedEvents, new Date());

    // Redraw only when the event changed. Rebuilding the card every minute
    // would move keyboard focus off its Check In button.
    if (eventInProgress !== shownEvent) {
        drawWidget(eventInProgress);
    }
}

function drawWidget(eventInProgress) {
    const { container, openEventModal } = latestOptions;

    shownEvent = eventInProgress;
    container.innerHTML = "";

    if (!eventInProgress) {
        const emptyState = document.createElement("div");

        emptyState.className = "check-in-widget-empty";
        emptyState.textContent = "No events happening now.";

        container.appendChild(emptyState);
        return;
    }

    container.appendChild(createHappeningNowCard(eventInProgress, openEventModal));
}

// The card is a plain box, not a button. The Check In button on it is the one
// thing to press, and it opens the event window with all the details.
function createHappeningNowCard(event, openEventModal) {
    const card = document.createElement("div");
    const details = document.createElement("div");
    const isPrivate = event.is_public === false;

    card.className = [
        "upcoming-event-card",
        "check-in-widget-card",
        isPrivate ? "private-event-card" : getEventColorClass(event.event_type)
    ].join(" ");
    details.className = "check-in-widget-details";
    card.appendChild(details);

    // A private event can't be opened, so it shows no details and no button.
    if (isPrivate) {
        details.appendChild(createText("upcoming-event-title", "Private event"));
        return card;
    }

    details.append(
        createText("upcoming-event-title", event.title || "Untitled event"),
        createText("upcoming-event-time", formatEventTime(event))
    );
    card.appendChild(createCheckInButton(event, openEventModal));

    return card;
}

function createText(className, text) {
    const element = document.createElement("div");

    element.className = className;
    element.textContent = text;

    return element;
}

// The Check In button opens the event window (the details and the check-in
// form) and puts the cursor in the form's First Name field, so a visitor can
// start typing right away.
function createCheckInButton(event, openEventModal) {
    const checkInButton = document.createElement("button");

    checkInButton.type = "button";
    checkInButton.className = "check-in-widget-button";
    checkInButton.textContent = "Check In";
    checkInButton.setAttribute(
        "aria-label",
        `Check In to ${event.title || "Untitled event"}`
    );
    checkInButton.addEventListener("click", async () => {
        // openEventModal waits for the host's name before it shows the
        // window, so wait for it; a field can't take focus while hidden.
        await openEventModal(event);
        focusCheckInFirstName();
    });

    return checkInButton;
}

function focusCheckInFirstName() {
    const firstNameField = document.getElementById(CHECK_IN_FIRST_NAME_ID);

    // Skip it if the field isn't on screen (for example, the window didn't
    // open, or the check-in form is hidden).
    if (firstNameField && firstNameField.getClientRects().length > 0) {
        firstNameField.focus();
    }
}

// An event is in progress from its start time up to, but not including, its
// end time. If events overlap, the one that started last is shown.
function findEventInProgress(reservedEvents, now) {
    const nowTime = now.getTime();
    let found = null;
    let foundStart = -Infinity;

    reservedEvents.forEach(event => {
        if (event.is_closure) return;

        const start = new Date(event.start_time).getTime();
        const end = new Date(event.end_time).getTime();

        if (Number.isNaN(start) || Number.isNaN(end)) return;

        if (start <= nowTime && nowTime < end && start > foundStart) {
            found = event;
            foundStart = start;
        }
    });

    return found;
}
