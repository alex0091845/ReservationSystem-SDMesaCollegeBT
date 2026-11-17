import { createEventCard } from "./eventCards.js";

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

    drawWidget(findEventInProgress(options.reservedEvents, new Date()), new Date());

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

    const now = new Date();
    const eventInProgress = findEventInProgress(reservedEvents, now);

    // Redraw only when the event changed. Rebuilding the card every minute
    // would move keyboard focus off it.
    if (eventInProgress !== shownEvent) {
        drawWidget(eventInProgress, now);
    } else {
        showDate(now);
    }
}

function drawWidget(eventInProgress, now) {
    const { container, openEventModal } = latestOptions;

    shownEvent = eventInProgress;
    showDate(now);
    container.innerHTML = "";

    if (!eventInProgress) {
        const emptyState = document.createElement("div");

        emptyState.className = "check-in-widget-empty";
        emptyState.textContent = "No events happening now.";

        container.appendChild(emptyState);
        return;
    }

    const card = createEventCard({
        event: eventInProgress,
        classNames: ["check-in-widget-card"],
        onClick: openEventModal
    });

    // The card is already a <button>, and a button can't go inside another
    // button (invalid HTML that confuses screen readers). So the card and the
    // Check In button sit side by side in one wrapper, and the CSS places the
    // Check In button on top of the card's right end.
    const item = document.createElement("div");

    item.className = "check-in-widget-item";
    item.appendChild(card);

    // A private event can't be opened, so it gets no Check In button.
    if (!card.classList.contains("private-event-card")) {
        item.classList.add("with-check-in-button");
        item.appendChild(createCheckInButton(eventInProgress, openEventModal));
    }

    container.appendChild(item);
}

// The Check In button opens the same event window as the card does, then
// puts the cursor in the check-in form's First Name field, so a visitor can
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

function showDate(now) {
    const { dateLabel, formatDate } = latestOptions;

    if (!dateLabel) {
        return;
    }

    const text = formatDate(now);

    if (dateLabel.textContent !== text) {
        dateLabel.textContent = text;
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
