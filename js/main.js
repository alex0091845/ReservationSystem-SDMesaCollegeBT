import { getAttendees, getCurrentSession, getEvents, getUsers, logoutUser, updateEvent } from "./api.js";
import { sortReservedEvents } from "./utils/dateUtils.js";
import { validateReservationData } from "./utils/reservationValidation.js";
import { renderCalendar } from "./ui/monthView.js";
import { renderWeekView } from "./ui/weekView.js";
import { renderUpcomingEvents } from "./ui/upcomingEvents.js";
import { renderMyEvents } from "./ui/myEvents.js";
import { renderCheckInEvents } from "./ui/checkInEvents.js";
import { renderEventAttendees } from "./ui/attendees.js";
import { createModalController } from "./ui/modal.js";


// Makes all page elements accessible in one place
const elements = {
    leftColumn: document.querySelector(".left-column"),
    datesContainer: document.getElementById("dates"),
    calendarTitle: document.getElementById("calendarTitle"),
    weekViewTitle: document.getElementById("weekViewTitle"),
    prevMonthBtn: document.getElementById("prevMonthBtn"),
    nextMonthBtn: document.getElementById("nextMonthBtn"),
    prevWeekBtn: document.getElementById("prevWeekBtn"),
    nextWeekBtn: document.getElementById("nextWeekBtn"),
    sidePanel: document.getElementById("sidePanel"),
    weekViewWrapper: document.getElementById("weekViewWrapper"),
    upcomingEventsList: document.getElementById("upcomingEventsList"),
    myEventsWidget: document.getElementById("myEventsWidget"),
    myEventsList: document.getElementById("myEventsList"),
    checkInWidget: document.getElementById("checkInWidget"),
    checkInWidgetDate: document.getElementById("checkInWidgetDate"),
    checkInWidgetList: document.getElementById("checkInWidgetList"),
    facultyReservationModalOverlay: document.getElementById("facultyReservationModalOverlay"),
    facultyReservationModalCloseBtn: document.getElementById("facultyReservationModalCloseBtn"),
    facultyReservationCancelBtn: document.getElementById("facultyReservationCancelBtn"),
    facultyReservationForm: document.getElementById("facultyReservationForm"),
    facultyReservationSubmitBtn: document.getElementById("facultyReservationSubmitBtn"),
    facultyReservationStatus: document.getElementById("facultyReservationStatus"),
    facultyReservationAttendeesList: document.getElementById("facultyReservationAttendeesList"),
    facultyReservationAttendeesCount: document.getElementById("facultyReservationAttendeesCount"),
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
    eventCheckInSection: document.getElementById("eventCheckInSection"),
    eventCheckInForm: document.getElementById("eventCheckInForm"),
    eventCheckInSubmitBtn: document.getElementById("eventCheckInSubmitBtn"),
    eventCheckInStatus: document.getElementById("eventCheckInStatus"),
    openReservationModalBtn: document.getElementById("openReservationModalBtn"),
    loginBtn: document.getElementById("loginBtn"),
    navUserIdentity: document.getElementById("navUserIdentity"),
    navUserInitials: document.getElementById("navUserInitials"),
    navUserName: document.getElementById("navUserName")
};

let isFacultyLoggedIn =
    sessionStorage.getItem("facultyLoggedIn") === "true";
let currentUserId = sessionStorage.getItem("currentUserId");
let currentUserRole = sessionStorage.getItem("currentUserRole") || "";
const largeScreenQuery = window.matchMedia("(min-width: 1680px)");

if (elements.openReservationModalBtn) {
    elements.openReservationModalBtn.disabled = !isFacultyLoggedIn;
    elements.openReservationModalBtn.title = !isFacultyLoggedIn
        ? "Faculty login required"
        : "";
}

// New event loading system
let reservedEvents = [];
let attendees = [];
let currentUser = null;
let selectedFacultyReservation = null;

async function loadEvents() {
    await syncSessionFromBackend();
    await loadCurrentUser();

    try {
        reservedEvents = await getEvents();

        sortReservedEvents(reservedEvents);
    } catch (error) {
        console.error("Error loading events:", error);

        reservedEvents = [];
    }

    await loadAttendees();

    init();
}

loadEvents();

async function loadCurrentUser() {
    if (!isFacultyLoggedIn || isCurrentSessionAdmin() || !currentUserId) {
        return;
    }

    if (currentUser && String(currentUser.id) === String(currentUserId)) {
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
    updateCreateReservationButtonState();
    renderCurrentUserIdentity();
    renderSideWidget();

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

async function loadAttendees() {
    if (!isFacultyLoggedIn || isCurrentSessionAdmin()) {
        attendees = [];
        return;
    }

    try {
        attendees = await getAttendees();
    } catch (error) {
        console.error("Error loading attendees:", error);
        attendees = [];
    }
}

async function syncSessionFromBackend() {
    try {
        const sessionUser = await getCurrentSession();

        if (!sessionUser?.id) {
            return;
        }

        storeSessionUser(sessionUser);

        if (!isAdminUser(sessionUser)) {
            currentUser = sessionUser;
        }
    } catch (error) {
        if (error.status === 401) {
            clearStoredSession();
        }
    }
}

function storeSessionUser(user) {
    isFacultyLoggedIn = true;
    currentUserId = String(user.id);
    currentUserRole = getUserRoleName(user);

    sessionStorage.setItem("facultyLoggedIn", "true");
    sessionStorage.setItem("currentUserId", currentUserId);
    sessionStorage.setItem("currentUserEmail", user.email || "");
    sessionStorage.setItem("currentUserRole", currentUserRole);

    if (currentUserRole.toLowerCase() === "admin") {
        sessionStorage.setItem("adminLoggedIn", "true");
    } else {
        sessionStorage.removeItem("adminLoggedIn");
    }
}

function clearStoredSession() {
    isFacultyLoggedIn = false;
    currentUserId = null;
    currentUserRole = "";
    currentUser = null;

    sessionStorage.removeItem("adminLoggedIn");
    sessionStorage.removeItem("facultyLoggedIn");
    sessionStorage.removeItem("currentUserId");
    sessionStorage.removeItem("currentUserEmail");
    sessionStorage.removeItem("currentUserRole");
}

function renderSideWidget() {
    syncSideWidgetPlacement();

    const hasFacultyWidget = renderFacultyMyEvents();
    const hasCheckInWidget = renderPublicCheckInWidget();

    elements.sidePanel?.classList.toggle(
        "has-side-widget",
        hasFacultyWidget || hasCheckInWidget
    );
}

function syncSideWidgetPlacement() {
    const widgetTarget = largeScreenQuery.matches
        ? elements.sidePanel
        : elements.leftColumn;

    if (!widgetTarget) {
        return;
    }

    getSideWidgets().forEach(widget => {
        if (widget.parentElement !== widgetTarget) {
            widgetTarget.appendChild(widget);
        }
    });
}

function getSideWidgets() {
    return [
        elements.myEventsWidget,
        elements.checkInWidget
    ].filter(Boolean);
}

function renderFacultyMyEvents() {
    if (!elements.myEventsWidget || !elements.myEventsList) {
        return false;
    }

    const shouldShowMyEvents =
        isFacultyLoggedIn &&
        currentUser &&
        !isAdminUser(currentUser);

    elements.myEventsWidget.hidden = !shouldShowMyEvents;

    if (!shouldShowMyEvents) {
        elements.myEventsList.innerHTML = "";
        return false;
    }

    renderMyEvents({
        container: elements.myEventsList,
        currentUser,
        reservedEvents,
        attendees,
        onEditEvent: openFacultyReservationEditModal
    });

    return true;
}

function renderPublicCheckInWidget() {
    if (!elements.checkInWidget || !elements.checkInWidgetList) {
        return false;
    }

    const shouldShowCheckIn = !isFacultyLoggedIn;

    elements.checkInWidget.hidden = !shouldShowCheckIn;

    if (!shouldShowCheckIn) {
        elements.checkInWidgetList.innerHTML = "";
        return false;
    }

    elements.checkInWidgetDate.textContent = formatSideWidgetDate(today);

    renderCheckInEvents({
        container: elements.checkInWidgetList,
        today,
        reservedEvents,
        openEventModal
    });

    return true;
}

function formatSideWidgetDate(date) {
    return date.toLocaleDateString([], {
        weekday: "long",
        month: "short",
        day: "numeric"
    });
}

function openFacultyReservationEditModal(reservation) {
    if (!currentUser || !isReservationOwnedByUser(reservation, currentUser)) {
        return;
    }

    selectedFacultyReservation = reservation;

    document.getElementById("facultyReservationId").value = reservation.id ?? "";
    document.getElementById("facultyReservationHostUserId").value =
        reservation.host_user_id ?? reservation.host_user?.id ?? currentUser.id ?? "";
    document.getElementById("facultyReservationTitle").value = reservation.title ?? "";
    document.getElementById("facultyReservationType").value = reservation.event_type ?? "";
    document.getElementById("facultyReservationDescription").value = reservation.description ?? "";
    document.getElementById("facultyReservationStart").value = formatDateTimeLocalValue(reservation.start_time);
    document.getElementById("facultyReservationEnd").value = formatDateTimeLocalValue(reservation.end_time);
    document.getElementById("facultyReservationDepartment").value = reservation.department ?? "";

    const accessValue = reservation.is_public ? "open" : "private";
    const accessInput = elements.facultyReservationForm.querySelector(
        `input[name="access"][value="${accessValue}"]`
    );

    if (accessInput) {
        accessInput.checked = true;
    }

    renderFacultyReservationAttendees(reservation);

    setFacultyReservationStatus("");
    setFacultyReservationSubmitting(false);
    elements.facultyReservationModalOverlay.classList.add("active");
    elements.facultyReservationModalOverlay.setAttribute("aria-hidden", "false");
}

function renderFacultyReservationAttendees(reservation) {
    renderEventAttendees({
        container: elements.facultyReservationAttendeesList,
        countElement: elements.facultyReservationAttendeesCount,
        event: reservation,
        attendees
    });
}

function closeFacultyReservationEditModal() {
    blurFocusedElementInside(elements.facultyReservationModalOverlay);

    elements.facultyReservationModalOverlay.classList.remove("active");
    elements.facultyReservationModalOverlay.setAttribute("aria-hidden", "true");

    selectedFacultyReservation = null;
    elements.facultyReservationForm.reset();
    setFacultyReservationStatus("");
    setFacultyReservationSubmitting(false);
}

function blurFocusedElementInside(container) {
    if (container?.contains(document.activeElement)) {
        document.activeElement.blur();
    }
}

function setFacultyReservationStatus(message, statusType = "") {
    if (!elements.facultyReservationStatus) {
        return;
    }

    elements.facultyReservationStatus.textContent = message;
    elements.facultyReservationStatus.classList.toggle("error", statusType === "error");
    elements.facultyReservationStatus.classList.toggle("success", statusType === "success");
}

function setFacultyReservationSubmitting(isSubmitting) {
    if (!elements.facultyReservationSubmitBtn) {
        return;
    }

    elements.facultyReservationSubmitBtn.disabled = isSubmitting;
    elements.facultyReservationSubmitBtn.textContent = isSubmitting
        ? "Saving..."
        : "Save Changes";
}

function toIsoDateTimeValue(value) {
    if (!value) {
        return "";
    }

    const date = new Date(value);

    return Number.isNaN(date.getTime()) ? "" : date.toISOString();
}

async function handleFacultyReservationSubmit(event) {
    event.preventDefault();

    if (!selectedFacultyReservation || !currentUser) {
        return;
    }

    if (!elements.facultyReservationForm.reportValidity()) {
        return;
    }

    const formData = new FormData(elements.facultyReservationForm);
    const startTime = toIsoDateTimeValue(formData.get("start"));
    const endTime = toIsoDateTimeValue(formData.get("end"));
    const hostUserId = formData.get("host_user_id") || currentUser.id;

    const reservationData = {
        ...selectedFacultyReservation,
        id: formData.get("id"),
        host_user_id: hostUserId,
        host_user: {
            ...(selectedFacultyReservation.host_user || {}),
            id: hostUserId,
            email: currentUser.email,
            first_name: currentUser.first_name,
            last_name: currentUser.last_name,
            role_name: currentUser.role_name,
            enabled: currentUser.enabled
        },
        start_time: startTime,
        end_time: endTime,
        event_type: formData.get("type"),
        description: formData.get("description"),
        title: formData.get("title"),
        department: formData.get("department"),
        is_public: formData.get("access") === "open"
    };

    const validation = validateReservationData({
        reservationData,
        existingReservations: reservedEvents,
        users: currentUser ? [currentUser] : [],
        requireId: true
    });

    if (!validation.isValid) {
        setFacultyReservationStatus(validation.message, "error");
        return;
    }

    setFacultyReservationStatus("");
    setFacultyReservationSubmitting(true);

    try {
        const updatedReservation = await updateEvent(reservationData);
        const reservationToRender = updatedReservation || reservationData;

        reservedEvents = reservedEvents.map(reservation => {
            return String(reservation.id) === String(reservationToRender.id)
                ? reservationToRender
                : reservation;
        });

        sortReservedEvents(reservedEvents);
        renderAll();
        closeFacultyReservationEditModal();
    } catch (error) {
        console.error("Could not update reservation:", error);
        setFacultyReservationStatus(
            error.message || "Could not save reservation. Please try again.",
            "error"
        );
    } finally {
        setFacultyReservationSubmitting(false);
    }
}

function isReservationOwnedByUser(reservation, user) {
    const reservationHostId =
        reservation.host_user_id ??
        reservation.host_user?.id;

    if (
        reservationHostId !== undefined &&
        user.id !== undefined &&
        String(reservationHostId) === String(user.id)
    ) {
        return true;
    }

    const reservationHostEmail = reservation.host_user?.email;

    return (
        reservationHostEmail &&
        user.email &&
        reservationHostEmail.toLowerCase() === user.email.toLowerCase()
    );
}

function formatDateTimeLocalValue(value) {
    if (!value) {
        return "";
    }

    const date = new Date(value);

    if (Number.isNaN(date.getTime())) {
        return "";
    }

    const offsetDate = new Date(
        date.getTime() - (date.getTimezoneOffset() * 60000)
    );

    return offsetDate.toISOString().slice(0, 16);
}

function updateCreateReservationButtonState() {
    if (!elements.openReservationModalBtn) {
        return;
    }

    const currentUserDisabled = currentUser?.enabled === false;

    elements.openReservationModalBtn.disabled = !isFacultyLoggedIn || currentUserDisabled;
    elements.openReservationModalBtn.title = !isFacultyLoggedIn
        ? "Faculty login required"
        : currentUserDisabled
            ? "This user is disabled and cannot create reservations"
            : "";
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
    return getUserRoleName(user).toLowerCase() === "admin";
}

function getUserRoleName(user) {
    return user.role_name || user.role || user.user_roles?.name || "";
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

    elements.facultyReservationModalCloseBtn.addEventListener(
        "click",
        closeFacultyReservationEditModal
    );

    elements.facultyReservationCancelBtn.addEventListener(
        "click",
        closeFacultyReservationEditModal
    );

    elements.facultyReservationForm.addEventListener(
        "submit",
        handleFacultyReservationSubmit
    );

    if (largeScreenQuery.addEventListener) {
        largeScreenQuery.addEventListener(
            "change",
            syncSideWidgetPlacement
        );
    } else {
        largeScreenQuery.addListener(syncSideWidgetPlacement);
    }

    // Allows event modal to be closed by clicking background
    bindBackdropClose(elements.eventModalOverlay, closeEventModal);
    bindBackdropClose(
        elements.facultyReservationModalOverlay,
        closeFacultyReservationEditModal
    );

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

            if (
                event.key === "Escape" &&
                elements.facultyReservationModalOverlay.classList.contains("active")
            ) {
                closeFacultyReservationEditModal();
            }
        }
    );

    if (elements.loginBtn) {
        elements.loginBtn.textContent = isFacultyLoggedIn ? "Sign Out" : "Faculty Login";

        elements.loginBtn.addEventListener(
            "click",
            async () => {
                if (isFacultyLoggedIn) {
                    try {
                        await logoutUser();
                    } catch (error) {
                        console.error("Could not log out on backend:", error);
                    }

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
