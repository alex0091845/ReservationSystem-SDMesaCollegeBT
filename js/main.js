import { renderCalendar } from "./ui/monthView.js";
import { renderWeekView } from "./ui/weekView.js";
import { renderUpcomingEvents } from "./ui/upcomingEvents.js";
import { createModalController } from "./ui/modal.js";

// Makes all page elements accessible in one place
const elements = {
    datesContainer: document.getElementById("dates"),
    calendarTitle: document.getElementById("calendarTitle"),
    weekViewTitle: document.getElementById("weekViewTitle"),
    prevMonthBtn: document.getElementById("prevMonthBtn"),
    nextMonthBtn: document.getElementById("nextMonthBtn"),
    prevWeekBtn: document.getElementById("prevWeekBtn"),
    nextWeekBtn: document.getElementById("nextWeekBtn"),
    weekViewWrapper: document.getElementById("weekViewWrapper"),
    upcomingEventsList: document.getElementById("upcomingEventsList"),
    eventModalOverlay: document.getElementById("eventModalOverlay"),
    modalCloseBtn: document.getElementById("modalCloseBtn"),
    modalEventTitle: document.getElementById("modalEventTitle"),
    modalEventDepartment: document.getElementById("modalEventDepartment"),
    modalOrganizer: document.getElementById("modalOrganizer"),
    modalDate: document.getElementById("modalDate"),
    modalTime: document.getElementById("modalTime"),
    modalDescription: document.getElementById("modalDescription"),
    loginBtn: document.getElementById("loginBtn")
};

// New event loading system
let reservedEvents = {};

async function loadEvents() {
    try {
        const response = await fetch("./data/events.json");

        if (!response.ok) {
            throw new Error("Could not load events.json");
        }

        reservedEvents = await response.json();
        init();
    } catch (error) {
        console.error("Error loading events:", error);
    }
}

loadEvents();

// Initializes selected date to today's date
const today = new Date();
const state = {
    selectedDate: new Date(today.getFullYear(), today.getMonth(), today.getDate()),
    currentYear: today.getFullYear(),
    currentMonth: today.getMonth()
};

// Pulls open and close functions from modal file
const { openEventModal, closeEventModal } = createModalController(elements);

// Syncs calendar widget date with week-view widget date
function syncCalendarToSelectedDate() {
    state.currentYear = state.selectedDate.getFullYear();
    state.currentMonth = state.selectedDate.getMonth();
}

// Changes selected date and updates calendar widget
function setSelectedDate(date) {
    state.selectedDate = date;
    syncCalendarToSelectedDate();
    renderAll();
}

// Changes selected date by given offset: useful for moving forward or back 1 week
function moveSelectedDateByDays(dayOffset) {
    const updated = new Date(state.selectedDate);
    updated.setDate(updated.getDate() + dayOffset);
    setSelectedDate(updated);
}

// Changes selected month by given offset
function changeMonth(monthOffset) {
    state.currentMonth += monthOffset;

    // If moving back one month from January, subtracts from the current year
    if (state.currentMonth < 0) {
        state.currentMonth = 11;
        state.currentYear--;
    }

    // If moving forward one month from December, adds to the current year
    if (state.currentMonth > 11) {
        state.currentMonth = 0;
        state.currentYear++;
    }

    // Finds number of days in new month and restricts output to last valid day
    const daysInMonth = new Date(state.currentYear, state.currentMonth + 1, 0).getDate();
    const safeDay = Math.min(state.selectedDate.getDate(), daysInMonth);
    state.selectedDate = new Date(state.currentYear, state.currentMonth, safeDay);

    renderAll();
}

// Draws all the page elements
function renderAll() {
    renderCalendar({
        datesContainer: elements.datesContainer,
        calendarTitle: elements.calendarTitle,
        currentYear: state.currentYear,
        currentMonth: state.currentMonth,
        selectedDate: state.selectedDate,
        reservedEvents,
        onSelectDate: setSelectedDate
    });

    renderWeekView({
        weekViewWrapper: elements.weekViewWrapper,
        weekViewTitle: elements.weekViewTitle,
        selectedDate: state.selectedDate,
        reservedEvents,
        onSelectDate: setSelectedDate,
        openEventModal
    });

    renderUpcomingEvents(
        elements.upcomingEventsList,
        today,
        reservedEvents,
        openEventModal
    );
}

// Starts event listeners for button and modal interactivity
function bindEvents() {
    elements.prevMonthBtn.addEventListener("click", () => changeMonth(-1));
    elements.nextMonthBtn.addEventListener("click", () => changeMonth(1));
    elements.prevWeekBtn.addEventListener("click", () => moveSelectedDateByDays(-7));
    elements.nextWeekBtn.addEventListener("click", () => moveSelectedDateByDays(7));

    elements.modalCloseBtn.addEventListener("click", closeEventModal);

    // Allows event modal to be closed by clicking background
    elements.eventModalOverlay.addEventListener("click", event => {
        if (event.target === elements.eventModalOverlay) {
            closeEventModal();
        }
    });

    // Allows event modal to be closed with escape key
    document.addEventListener("keydown", event => {
        if (event.key === "Escape" && elements.eventModalOverlay.classList.contains("active")) {
            closeEventModal();
        }
    });

    if (elements.loginBtn) {
        elements.loginBtn.addEventListener("click", () => {
            window.location.href = "login.html";
        });
    }
}

function init() {
    syncCalendarToSelectedDate();
    bindEvents();
    renderAll();
}