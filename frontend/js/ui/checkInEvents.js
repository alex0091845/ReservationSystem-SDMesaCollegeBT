import { createEventCard } from "./eventCards.js";

// How often the widget re-checks the clock. It only compares "now" with the
// events that are already loaded, so it makes no network calls.
const CLOCK_CHECK_MS = 60 * 1000;

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
    removeCheckInButton();

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

    container.appendChild(card);

    // A private event can't be opened, so it gets no Check In button.
    if (!card.classList.contains("private-event-card")) {
        addCheckInButton(eventInProgress, openEventModal);
    }
}

// The Check In button sits at the right end of the widget heading, next to
// the title and date. It opens the same event window as the card does.
function addCheckInButton(event, openEventModal) {
    const header = findHeader();

    if (!header) {
        return;
    }

    const checkInButton = document.createElement("button");

    checkInButton.type = "button";
    checkInButton.className = "reservation-primary-btn check-in-widget-button";
    checkInButton.textContent = "Check In";
    checkInButton.setAttribute(
        "aria-label",
        `Check In to ${event.title || "Untitled event"}`
    );
    checkInButton.addEventListener("click", () => openEventModal(event));

    header.appendChild(checkInButton);
}

function removeCheckInButton() {
    const header = findHeader();

    if (header) {
        header.querySelectorAll(".check-in-widget-button").forEach(button => {
            button.remove();
        });
    }
}

function findHeader() {
    const widget = latestOptions.container.closest(".check-in-widget");

    return widget ? widget.querySelector(".check-in-widget-header") : null;
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
