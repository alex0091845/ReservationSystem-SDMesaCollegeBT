import { getEvents, getUsers, isUserDisabled } from "./api.js";
import { sortReservedEvents } from "./utils/dateUtils.js";
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
    modalDate: document.getElementById("modalDate"),
    modalTime: document.getElementById("modalTime"),
    modalDescription: document.getElementById("modalDescription"),
    modalEventType: document.getElementById("modalEventType"),
    modalIsPublic: document.getElementById("modalIsPublic"),
    modalHost: document.getElementById("modalHost"),
    openReservationModalBtn: document.getElementById("openReservationModalBtn"),
    loginBtn: document.getElementById("loginBtn"),
    navUserIdentity: document.getElementById("navUserIdentity"),
    navUserInitials: document.getElementById("navUserInitials"),
    navUserName: document.getElementById("navUserName")
};

const isFacultyLoggedIn =
    sessionStorage.getItem("facultyLoggedIn") === "true";
const currentUserId = sessionStorage.getItem("currentUserId");
const currentUserRole = sessionStorage.getItem("currentUserRole") || "";
const currentHostUserId = sessionStorage.getItem("currentUserId") || 1;
const currentUserDisabled = isUserDisabled(currentHostUserId);

if (elements.openReservationModalBtn) {
    elements.openReservationModalBtn.disabled = !isFacultyLoggedIn || currentUserDisabled;
    elements.openReservationModalBtn.title = !isFacultyLoggedIn
        ? "Faculty login required"
        : currentUserDisabled
            ? "This user is disabled and cannot create reservations"
            : "";
}

// New event loading system
let reservedEvents = [];
let currentUser = null;

async function loadEvents() {
    await loadCurrentUser();

    try {
        reservedEvents = await getEvents();

        sortReservedEvents(reservedEvents);
    } catch (error) {
        console.error("Error loading events:", error);

        reservedEvents = [];
    }

    init();
}

loadEvents();

async function loadCurrentUser() {
    if (!isFacultyLoggedIn || isCurrentSessionAdmin() || !currentUserId) {
        return;
    }

    try {
        const users = await getUsers();

        currentUser = users.find(user => {
            return String(user.id) === String(currentUserId);
        }) || null;
    } catch (error) {
        console.error("Error loading current user:", error);
        currentUser = null;
    }
}

// Initializes selected date to today's date
const today = new Date();

const state = {
    selectedDate: new Date(
        today.getFullYear(),
        today.getMonth(),
        today.getDate()
    ),

    currentYear: today.getFullYear(),
    currentMonth: today.getMonth()
};

// Pulls open and close functions from modal file
const {
    openEventModal,
    closeEventModal
} = createModalController(elements);

function bindBackdropClose(overlay, closeModal) {
    let pointerStartedOnBackdrop = false;

    overlay.addEventListener(
        "pointerdown",
        event => {
            pointerStartedOnBackdrop = event.target === overlay;
        }
    );

    overlay.addEventListener(
        "click",
        event => {
            if (pointerStartedOnBackdrop && event.target === overlay) {
                closeModal();
            }

            pointerStartedOnBackdrop = false;
        }
    );
}

// Syncs calendar widget date with week-view widget date
function syncCalendarToSelectedDate() {
    state.currentYear =
        state.selectedDate.getFullYear();

    state.currentMonth =
        state.selectedDate.getMonth();
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

    updated.setDate(
        updated.getDate() + dayOffset
    );

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
    const daysInMonth = new Date(
        state.currentYear,
        state.currentMonth + 1,
        0
    ).getDate();

    const safeDay = Math.min(
        state.selectedDate.getDate(),
        daysInMonth
    );

    state.selectedDate = new Date(
        state.currentYear,
        state.currentMonth,
        safeDay
    );

    renderAll();
}

// Draws all the page elements
function renderAll() {
    renderCurrentUserIdentity();

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

function renderCurrentUserIdentity() {
    if (!elements.navUserIdentity) {
        return;
    }

    const shouldShowUser =
        isFacultyLoggedIn &&
        currentUser &&
        !isAdminUser(currentUser);

    elements.navUserIdentity.hidden = !shouldShowUser;

    if (!shouldShowUser) {
        return;
    }

    const userName = getUserFullName(currentUser);

    elements.navUserName.textContent = userName;
    elements.navUserInitials.textContent = getUserInitials(currentUser);
    elements.navUserIdentity.setAttribute(
        "aria-label",
        `Signed in as ${userName}`
    );
}

function getUserFullName(user) {
    return `${user.first_name || ""} ${user.last_name || ""}`.trim() || user.email || "Signed-in user";
}

function getUserInitials(user) {
    const nameParts = [
        user.first_name,
        user.last_name
    ].filter(Boolean);

    if (nameParts.length > 0) {
        return nameParts
            .map(namePart => namePart.trim().charAt(0))
            .join("")
            .slice(0, 2)
            .toUpperCase();
    }

    return (user.email || "U").charAt(0).toUpperCase();
}

function isCurrentSessionAdmin() {
    return currentUserRole.toLowerCase() === "admin";
}

function isAdminUser(user) {
    return (user.role_name || user.role || "").toLowerCase() === "admin";
}

// Starts event listeners for button and modal interactivity
function bindEvents() {
    elements.prevMonthBtn.addEventListener(
        "click",
        () => changeMonth(-1)
    );

    elements.nextMonthBtn.addEventListener(
        "click",
        () => changeMonth(1)
    );

    elements.prevWeekBtn.addEventListener(
        "click",
        () => moveSelectedDateByDays(-7)
    );

    elements.nextWeekBtn.addEventListener(
        "click",
        () => moveSelectedDateByDays(7)
    );

    elements.modalCloseBtn.addEventListener(
        "click",
        closeEventModal
    );

    // Allows event modal to be closed by clicking background
    bindBackdropClose(elements.eventModalOverlay, closeEventModal);

    // Allows event modal to be closed with escape key
    document.addEventListener(
        "keydown",
        event => {
            if (
                event.key === "Escape" &&
                elements.eventModalOverlay.classList.contains("active")
            ) {
                closeEventModal();
            }
        }
    );

    if (elements.loginBtn) {
        elements.loginBtn.textContent = isFacultyLoggedIn ? "Sign Out" : "Faculty Login";

        elements.loginBtn.addEventListener(
            "click",
            () => {
                if (isFacultyLoggedIn) {
                    sessionStorage.removeItem("adminLoggedIn");
                    sessionStorage.removeItem("facultyLoggedIn");
                    sessionStorage.removeItem("currentUserId");
                    sessionStorage.removeItem("currentUserEmail");
                    sessionStorage.removeItem("currentUserRole");

                    window.location.href = "index.html";
                    return;
                }

                window.location.href = "login.html";
            }
        );
    }


    
}

function init() {
    syncCalendarToSelectedDate();

    bindEvents();

    renderAll();
}
