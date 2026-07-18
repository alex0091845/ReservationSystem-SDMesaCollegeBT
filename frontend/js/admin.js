import {
    getEvents,
    getAttendees,
    getEventTypes,
    getUsers,
    createEvents,
    createUser,
    updateUser,
    updateEvent,
    deleteEvent,
    disableUser,
    enableUser,
    logoutUser,
    getCurrentSession
} from "./api.js";
import { validateReservationData } from "./utils/reservationValidation.js";
import { renderEventAttendees } from "./ui/attendees.js";
import { createEventCard } from "./ui/eventCards.js";
import { renderEventTypeOptions } from "./ui/eventTypeOptions.js";
import { createReservationTimePicker } from "./ui/reservationTimePicker.js";
import {
    applyReservationFormDraft,
    collectReservationFormDraft,
    createReservationDraftAutosave
} from "./ui/reservationDrafts.js";

const facultyUserList = document.getElementById("facultyUserList");
const userCount = document.getElementById("userCount");
const userSearchInput = document.getElementById("userSearchInput");
const userRoleFilter = document.getElementById("userRoleFilter");
const userSortSelect = document.getElementById("userSortSelect");
const adminUserDetails = document.getElementById("adminUserDetails");
const createUserBtn = document.getElementById("createUserBtn");

const logoutBtn = document.getElementById("logoutBtn");

const userModalOverlay = document.getElementById("userModalOverlay");
const userModalCloseBtn = document.getElementById("userModalCloseBtn");
const userCancelBtn = document.getElementById("userCancelBtn");
const userForm = document.getElementById("userForm");

const userModalTitle = document.getElementById("userModalTitle");
const userModalSubtitle = document.getElementById("userModalSubtitle");
const userSubmitBtn = document.getElementById("userSubmitBtn");

const disableUserModalOverlay = document.getElementById("disableUserModalOverlay");
const disableUserModalCloseBtn = document.getElementById("disableUserModalCloseBtn");
const disableUserCancelBtn = document.getElementById("disableUserCancelBtn");
const disableUserConfirmBtn = document.getElementById("disableUserConfirmBtn");
const disableUserModalTitle = document.getElementById("disableUserModalTitle");
const disableUserModalSubtitle = document.getElementById("disableUserModalSubtitle");
const disableUserActionVerb = document.getElementById("disableUserActionVerb");
const disableUserName = document.getElementById("disableUserName");
const disableUserWarning = document.getElementById("disableUserWarning");

const adminReservationModalOverlay = document.getElementById("adminReservationModalOverlay");
const adminReservationModalCloseBtn = document.getElementById("adminReservationModalCloseBtn");
const adminReservationCancelBtn = document.getElementById("adminReservationCancelBtn");
const adminReservationDeleteBtn = document.getElementById("adminReservationDeleteBtn");
const adminReservationDeleteSeriesBtn = document.getElementById("adminReservationDeleteSeriesBtn");
const adminReservationForm = document.getElementById("adminReservationForm");
const adminReservationSubmitBtn = document.getElementById("adminReservationSubmitBtn");
const adminReservationStatus = document.getElementById("adminReservationStatus");
const adminReservationAttendeesList = document.getElementById("adminReservationAttendeesList");
const adminReservationAttendeesCount = document.getElementById("adminReservationAttendeesCount");
const adminReservationType = document.getElementById("adminReservationType");

let users = [];
let reservations = [];
let attendees = [];
let eventTypes = [];
let selectedUser = null;
let selectedReservation = null;
let userPendingStatusChange = null;
let pendingStatusAction = "disable";
let modalMode = "create";
let userSearchTerm = "";
let userRoleValue = "all";
let userSortValue = "name-asc";
const adminReservationTimePicker = createReservationTimePicker({
    container: document.getElementById("adminReservationTimePicker"),
    summaryElement: document.getElementById("adminReservationTimeSummary"),
    startInput: document.getElementById("adminReservationStart"),
    endInput: document.getElementById("adminReservationEnd")
});
const adminReservationDraftAutosave = createReservationDraftAutosave({
    form: adminReservationForm,
    timePicker: adminReservationTimePicker,
    draftType: "edit",
    getSourceEventId: () => selectedReservation?.id,
    getHostUserId: () => {
        return document.getElementById("adminReservationHostUserId")?.value ||
            selectedUser?.id;
    },
    collectPayload: () => collectReservationFormDraft(
        adminReservationForm,
        adminReservationTimePicker
    ),
    applyPayload: payload => {
        applyReservationFormDraft(
            adminReservationForm,
            adminReservationTimePicker,
            payload
        );
    }
});

let isAdminLoggedIn =
    sessionStorage.getItem("adminLoggedIn") === "true";

async function bootstrapAdminDashboard() {
    const canAccessAdmin = await verifyAdminSession();

    if (!canAccessAdmin) {
        window.location.href = "login.html";
        return;
    }

    loadDashboardData();
}

async function verifyAdminSession() {
    try {
        const sessionUser = await getCurrentSession();

        if (!isAdminUser(sessionUser)) {
            clearStoredSession();
            return false;
        }

        storeSessionUser(sessionUser);
        return true;
    } catch (error) {
        if (!error.status && isAdminLoggedIn) {
            return true;
        }

        clearStoredSession();
        return false;
    }
}

function storeSessionUser(user) {
    isAdminLoggedIn = true;

    sessionStorage.setItem("facultyLoggedIn", "true");
    sessionStorage.setItem("adminLoggedIn", "true");
    sessionStorage.setItem("currentUserId", String(user.id));
    sessionStorage.setItem("currentUserEmail", user.email || "");
    sessionStorage.setItem("currentUserRole", getUserRoleName(user));
}

function clearStoredSession() {
    isAdminLoggedIn = false;

    sessionStorage.removeItem("adminLoggedIn");
    sessionStorage.removeItem("facultyLoggedIn");
    sessionStorage.removeItem("currentUserId");
    sessionStorage.removeItem("currentUserEmail");
    sessionStorage.removeItem("currentUserRole");
}

async function loadDashboardData() {
    try {
        users = await getUsers();
    } catch (error) {
        console.error("Could not load users for admin dashboard:", error);
        users = [];
    }

    try {
        reservations = await getEvents();
    } catch (error) {
        console.error("Could not load reservations for admin dashboard:", error);
        reservations = [];
    }

    try {
        attendees = await getAttendees();
    } catch (error) {
        console.error("Could not load attendees for admin dashboard:", error);
        attendees = [];
    }

    try {
        eventTypes = await getEventTypes();
    } catch (error) {
        console.error("Could not load event types for admin dashboard:", error);
        eventTypes = [];
    }

    renderEventTypeOptions(adminReservationType, eventTypes);

    renderUsers();
    renderUserDetails();
}

function renderUsers() {
    facultyUserList.innerHTML = "";

    const visibleUsers = getVisibleUsers();

    userCount.textContent = `${visibleUsers.length} of ${users.length} user${users.length === 1 ? "" : "s"}`;

    if (visibleUsers.length === 0) {
        const emptyState = document.createElement("div");
        emptyState.className = "faculty-user-empty";
        emptyState.textContent = "No users match the current search or filter.";
        facultyUserList.appendChild(emptyState);
        return;
    }

    visibleUsers.forEach(user => {
        const userCard = document.createElement("button");
        userCard.type = "button";
        userCard.className = "faculty-user-card";

        if (isSelectedUser(user)) {
            userCard.classList.add("selected");
        }

        if (!isUserEnabled(user)) {
            userCard.classList.add("disabled");
        }

        const userDetails = document.createElement("div");
        userDetails.className = "faculty-user-details";

        const userName = document.createElement("div");
        userName.className = "faculty-user-name";
        userName.textContent = getUserFullName(user);

        const userEmail = document.createElement("div");
        userEmail.className = "faculty-user-email";
        userEmail.textContent = user.email || "No email";

        userDetails.append(userName, userEmail);

        const userMeta = document.createElement("div");
        userMeta.className = "faculty-user-meta";

        const role = document.createElement("span");
        role.textContent = isUserEnabled(user) ? getUserRoleName(user) : "disabled";

        userMeta.append(role);
        userCard.append(userDetails, userMeta);

        userCard.addEventListener("click", () => {
            selectedUser = isSelectedUser(user) ? null : user;
            renderUsers();
            renderUserDetails();
        });

        facultyUserList.appendChild(userCard);
    });
}

function renderUserDetails() {
    adminUserDetails.innerHTML = "";

    if (!selectedUser) {
        const emptyDetails = document.createElement("div");
        emptyDetails.className = "admin-user-empty-state";
        emptyDetails.textContent = "Select a user to view reservations and actions.";
        adminUserDetails.appendChild(emptyDetails);
        return;
    }

    const selectedUserReservations = getReservationsForUser(selectedUser);

    const detailsHeader = document.createElement("div");
    detailsHeader.className = "selected-user-header";

    const detailsTitle = document.createElement("h2");
    detailsTitle.textContent = getUserFullName(selectedUser);

    const detailsSubtitle = document.createElement("p");
    detailsSubtitle.textContent = selectedUser.email || "No email";

    detailsHeader.append(detailsTitle, detailsSubtitle);

    const actionRow = document.createElement("div");
    actionRow.className = "selected-user-actions";

    const editButton = document.createElement("button");
    editButton.type = "button";
    editButton.className = "reservation-primary-btn";
    editButton.textContent = "Edit User";
    editButton.addEventListener("click", () => {
        openEditUserModal(selectedUser);
    });

    actionRow.append(editButton);

    if (!isAdminUser(selectedUser)) {
        const disableButton = document.createElement("button");
        disableButton.type = "button";
        disableButton.className = isUserEnabled(selectedUser)
            ? "reservation-secondary-btn disable-user-btn"
            : "reservation-secondary-btn enable-user-btn";
        disableButton.textContent = isUserEnabled(selectedUser)
            ? "Disable User"
            : "Enable User";
        disableButton.addEventListener("click", openUserStatusModal);

        actionRow.append(disableButton);
    }

    const reservationsTitle = document.createElement("h3");
    reservationsTitle.className = "selected-user-reservations-title";
    reservationsTitle.textContent = `Reservations (${selectedUserReservations.length})`;

    const reservationList = document.createElement("div");
    reservationList.className = "selected-user-reservation-list";

    if (selectedUserReservations.length === 0) {
        const emptyReservations = document.createElement("div");
        emptyReservations.className = "selected-user-empty-reservations";
        emptyReservations.textContent = "No reservations found for this user.";
        reservationList.appendChild(emptyReservations);
    } else {
        selectedUserReservations.forEach(reservation => {
            reservationList.appendChild(createReservationItem(reservation));
        });
    }

    adminUserDetails.append(
        detailsHeader,
        actionRow,
        reservationsTitle,
        reservationList
    );
}

function createReservationItem(reservation) {
    return createEventCard({
        event: reservation,
        classNames: ["selected-user-reservation-card"],
        titleFallback: "Untitled reservation",
        attendees,
        showAttendeeCount: true,
        onClick: openReservationEditModal
    });
}

function getReservationsForUser(user) {
    return reservations.filter(reservation => {
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
    });
}

function isSelectedUser(user) {
    return selectedUser && String(selectedUser.id) === String(user.id);
}

function getVisibleUsers() {
    const normalizedSearchTerm = userSearchTerm.trim().toLowerCase();

    return users
        .filter(user => {
            const nameMatches = getUserFullName(user)
                .toLowerCase()
                .includes(normalizedSearchTerm);
            const roleMatches =
                userRoleValue === "all" ||
                getUserRoleName(user).toLowerCase() === userRoleValue;

            return nameMatches && roleMatches;
        })
        .sort((firstUser, secondUser) => {
            const firstUserEnabled = isUserEnabled(firstUser);
            const secondUserEnabled = isUserEnabled(secondUser);

            if (firstUserEnabled !== secondUserEnabled) {
                return firstUserEnabled ? -1 : 1;
            }

            const [sortField, sortDirection] = userSortValue.split("-");
            const direction = sortDirection === "desc" ? -1 : 1;

            return (
                getSortValue(firstUser, sortField)
                    .localeCompare(getSortValue(secondUser, sortField), undefined, {
                        sensitivity: "base"
                    }) * direction
            );
        });
}

function getSortValue(user, sortField) {
    return getUserFullName(user);
}

function getUserFullName(user) {
    return `${user.first_name || ""} ${user.last_name || ""}`.trim() || "Unnamed user";
}

function getUserRoleName(user) {
    return user.role_name || user.role || user.user_roles?.name || "Faculty";
}

function isUserEnabled(user) {
    return user.enabled !== false;
}

function isAdminUser(user) {
    return getUserRoleName(user).toLowerCase() === "admin";
}

function openCreateUserModal() {
    modalMode = "create";
    selectedUser = null;
    renderUsers();
    renderUserDetails();

    userForm.reset();
    document.getElementById("userId").value = "";
    document.getElementById("userFormMessage").textContent = "";
    document.getElementById("userPasswordHint").textContent = "Required for new users.";

    userModalTitle.textContent = "Create User";
    userModalSubtitle.textContent = "Add a new faculty user";
    userSubmitBtn.textContent = "Create User";

    openUserModal();
}

function openEditUserModal(user) {
    modalMode = "edit";
    selectedUser = user;

    document.getElementById("userId").value = user.id ?? "";
    document.getElementById("userFirstName").value = user.first_name ?? "";
    document.getElementById("userLastName").value = user.last_name ?? "";
    document.getElementById("userEmail").value = user.email ?? "";
    document.getElementById("userPhone").value = user.phone ?? "";
    document.getElementById("userRole").value = getUserRoleName(user);
    document.getElementById("userPassword").value = "";
    document.getElementById("userFormMessage").textContent = "";
    document.getElementById("userPasswordHint").textContent = "Leave blank to keep the current password.";

    userModalTitle.textContent = "Edit User";
    userModalSubtitle.textContent = "Update faculty user information";
    userSubmitBtn.textContent = "Save Changes";

    openUserModal();
}

function openUserModal() {
    userModalOverlay.classList.add("active");
    userModalOverlay.setAttribute("aria-hidden", "false");
}

function closeUserModal() {
    userModalOverlay.classList.remove("active");
    userModalOverlay.setAttribute("aria-hidden", "true");

    userForm.reset();
}

function openUserStatusModal() {
    if (!selectedUser || isAdminUser(selectedUser)) {
        return;
    }

    userPendingStatusChange = selectedUser;
    pendingStatusAction = isUserEnabled(selectedUser) ? "disable" : "enable";
    setUserStatusModalContent();

    disableUserConfirmBtn.disabled = false;

    disableUserModalOverlay.classList.add("active");
    disableUserModalOverlay.setAttribute("aria-hidden", "false");
}

function setUserStatusModalContent() {
    const isEnableAction = pendingStatusAction === "enable";
    const actionLabel = isEnableAction ? "Enable User" : "Disable User";

    disableUserModalTitle.textContent = actionLabel;
    disableUserModalSubtitle.textContent = isEnableAction
        ? "Confirm account restoration"
        : "Confirm account restriction";
    disableUserActionVerb.textContent = isEnableAction ? "enable" : "disable";
    disableUserName.textContent = getUserFullName(userPendingStatusChange);
    disableUserWarning.textContent = isEnableAction
        ? "Enabled users can create new reservations."
        : "Disabled users cannot create new reservations.";
    disableUserWarning.classList.toggle("success", isEnableAction);

    disableUserConfirmBtn.textContent = actionLabel;
    disableUserConfirmBtn.classList.toggle("enable-user-btn", isEnableAction);
    disableUserConfirmBtn.classList.toggle("disable-user-btn", !isEnableAction);
}

function closeDisableUserModal() {
    disableUserModalOverlay.classList.remove("active");
    disableUserModalOverlay.setAttribute("aria-hidden", "true");

    userPendingStatusChange = null;
    pendingStatusAction = "disable";
    disableUserConfirmBtn.disabled = false;
    disableUserConfirmBtn.textContent = "Disable User";
    disableUserConfirmBtn.classList.remove("enable-user-btn");
    disableUserConfirmBtn.classList.add("disable-user-btn");
    disableUserWarning.classList.remove("success");
}

async function openReservationEditModal(reservation) {
    selectedReservation = reservation;

    document.getElementById("adminReservationId").value = reservation.id ?? "";
    document.getElementById("adminReservationHostUserId").value =
        reservation.host_user_id ?? reservation.host_user?.id ?? selectedUser?.id ?? "";
    document.getElementById("adminReservationTitle").value = reservation.title ?? "";
    renderEventTypeOptions(
        adminReservationType,
        eventTypes,
        { selectedValue: reservation.event_type ?? "" }
    );
    document.getElementById("adminReservationDescription").value = reservation.description ?? "";
    adminReservationTimePicker.setRange(
        reservation.start_time,
        reservation.end_time
    );
    document.getElementById("adminReservationDepartment").value = reservation.department ?? "";
    document.getElementById("adminReservationHost").value = getReservationHostName(reservation);

    const accessValue = reservation.is_public ? "open" : "private";
    const accessInput = adminReservationForm.querySelector(
        `input[name="access"][value="${accessValue}"]`
    );

    if (accessInput) {
        accessInput.checked = true;
    }

    renderAdminReservationAttendees(reservation);
    updateAdminReservationSeriesDeleteButton(reservation);

    setAdminReservationStatus("");
    setAdminReservationSubmitting(false);
    adminReservationModalOverlay.classList.add("active");
    adminReservationModalOverlay.setAttribute("aria-hidden", "false");
    await adminReservationDraftAutosave.activate();
}

function renderAdminReservationAttendees(reservation) {
    renderEventAttendees({
        container: adminReservationAttendeesList,
        countElement: adminReservationAttendeesCount,
        event: reservation,
        attendees
    });
}

function updateAdminReservationSeriesDeleteButton(reservation) {
    if (!adminReservationDeleteSeriesBtn) {
        return;
    }

    adminReservationDeleteSeriesBtn.hidden =
        getReservationSeries(reservation, reservations).length < 2;
}

function closeReservationEditModal({ flushDraft = true } = {}) {
    if (flushDraft) {
        adminReservationDraftAutosave.deactivate({ flush: true });
    } else {
        adminReservationDraftAutosave.deactivate();
    }

    adminReservationModalOverlay.classList.remove("active");
    adminReservationModalOverlay.setAttribute("aria-hidden", "true");

    selectedReservation = null;
    adminReservationForm.reset();
    adminReservationTimePicker.clear();
    updateAdminReservationSeriesDeleteButton(null);
    setAdminReservationStatus("");
    setAdminReservationSubmitting(false);
    setAdminReservationDeleting(false);
}

function bindBackdropClose(overlay, closeModal) {
    let pointerStartedOnBackdrop = false;

    overlay.addEventListener("pointerdown", event => {
        pointerStartedOnBackdrop = event.target === overlay;
    });

    overlay.addEventListener("click", event => {
        if (pointerStartedOnBackdrop && event.target === overlay) {
            closeModal();
        }

        pointerStartedOnBackdrop = false;
    });
}

function formatDateTimeLocalValue(value) {
    if (!value) {
        return "";
    }

    const date = new Date(value);

    if (Number.isNaN(date.getTime())) {
        return "";
    }

    const offsetDate = new Date(date.getTime() - (date.getTimezoneOffset() * 60000));

    return offsetDate.toISOString().slice(0, 16);
}

function setAdminReservationStatus(message, statusType = "") {
    if (!adminReservationStatus) {
        return;
    }

    adminReservationStatus.textContent = message;
    adminReservationStatus.classList.toggle("error", statusType === "error");
    adminReservationStatus.classList.toggle("success", statusType === "success");
}

function setAdminReservationSubmitting(isSubmitting) {
    if (!adminReservationSubmitBtn) {
        return;
    }

    adminReservationSubmitBtn.disabled = isSubmitting;
    adminReservationSubmitBtn.textContent = isSubmitting
        ? "Saving..."
        : "Save Changes";

    if (adminReservationDeleteBtn) {
        adminReservationDeleteBtn.disabled = isSubmitting;
    }

    if (adminReservationDeleteSeriesBtn) {
        adminReservationDeleteSeriesBtn.disabled = isSubmitting;
    }
}

function setAdminReservationDeleting(isDeleting, deleteMode = "reservation") {
    if (!adminReservationDeleteBtn) {
        return;
    }

    adminReservationDeleteBtn.disabled = isDeleting;
    adminReservationDeleteBtn.textContent = isDeleting
        ? (deleteMode === "series" ? "Deleting Series..." : "Deleting...")
        : "Delete Reservation";

    if (adminReservationDeleteSeriesBtn) {
        adminReservationDeleteSeriesBtn.disabled = isDeleting;
        adminReservationDeleteSeriesBtn.textContent = isDeleting
            ? (deleteMode === "series" ? "Deleting Series..." : "Deleting...")
            : "Delete Recurring Series";
    }

    if (adminReservationSubmitBtn) {
        adminReservationSubmitBtn.disabled = isDeleting;
    }

    if (adminReservationCancelBtn) {
        adminReservationCancelBtn.disabled = isDeleting;
    }
}

function getReservationHostName(reservation) {
    const firstName = reservation.host_user?.first_name ?? selectedUser?.first_name ?? "";
    const lastName = reservation.host_user?.last_name ?? selectedUser?.last_name ?? "";

    return `${firstName} ${lastName}`.trim();
}

function getUpdatedHostUser(hostName, fallbackHostUser) {
    const [firstName, ...lastNameParts] = hostName.trim().split(/\s+/);

    return {
        ...(fallbackHostUser || {}),
        id: fallbackHostUser?.id ?? selectedUser?.id,
        email: fallbackHostUser?.email ?? selectedUser?.email,
        first_name: firstName || fallbackHostUser?.first_name || selectedUser?.first_name || "",
        last_name: lastNameParts.join(" ") || fallbackHostUser?.last_name || selectedUser?.last_name || "",
        role_name: fallbackHostUser?.role_name ?? getUserRoleName(selectedUser || {}),
        enabled: fallbackHostUser?.enabled ?? selectedUser?.enabled ?? true
    };
}

userForm.addEventListener("submit", async event => {
    event.preventDefault();

    const userFormMessage = document.getElementById("userFormMessage");
    userFormMessage.textContent = "";

    const formData = new FormData(userForm);
    const userData = Object.fromEntries(formData.entries());
    const password = (userData.password || "").trim();

    if (modalMode === "create") {
        delete userData.id;

        if (!password) {
            userFormMessage.textContent = "A password is required for new users.";
            return;
        }

        try {
            const createdUser = await createUser(userData);
            users.push({
                ...createdUser,
                enabled: createdUser.enabled !== false
            });
        } catch (error) {
            console.error("Could not create user:", error);
            userFormMessage.textContent = getUserFormErrorMessage(error, "Could not create the user.");
            return;
        }
    }

    if (modalMode === "edit") {
        // A blank password on edit means "keep the current password" — don't send it.
        if (!password) {
            delete userData.password;
        }

        try {
            await updateUser(userData);
        } catch (error) {
            console.error("Could not update user:", error);
            userFormMessage.textContent = getUserFormErrorMessage(error, "Could not update the user.");
            return;
        }

        users = users.map(user => {
            if (String(user.id) === String(userData.id)) {
                const updatedUser = {
                    ...user,
                    ...userData
                };

                // Never keep the plaintext password in the in-memory list.
                delete updatedUser.password;
                selectedUser = updatedUser;

                return updatedUser;
            }

            return user;
        });
    }

    renderUsers();
    renderUserDetails();
    closeUserModal();
});

function getUserFormErrorMessage(error, fallback) {
    if (error?.status === 403) {
        return "You do not have permission to manage users.";
    }

    return error?.data?.error || error?.data?.message || fallback;
}

createUserBtn.addEventListener("click", openCreateUserModal);

adminReservationForm.addEventListener("submit", async event => {
    event.preventDefault();

    if (!selectedReservation) {
        return;
    }

    if (!adminReservationForm.reportValidity()) {
        return;
    }

    const formData = new FormData(adminReservationForm);
    const hostUserId = formData.get("host_user_id") || selectedUser?.id;
    const selectedTimeRanges = adminReservationTimePicker.getRanges();

    if (selectedTimeRanges.length === 0) {
        setAdminReservationStatus("Select at least one reservation time block.", "error");
        return;
    }

    const primaryRange = selectedTimeRanges[0];
    const recurrenceGroupId =
        selectedReservation.recurrence_group_id || null;

    const reservationData = {
        ...selectedReservation,
        id: formData.get("id"),
        host_user_id: hostUserId,
        host_user: getUpdatedHostUser(
            formData.get("host") || "",
            selectedReservation.host_user
        ),
        start_time: primaryRange.start.toISOString(),
        end_time: primaryRange.end.toISOString(),
        event_type: formData.get("type"),
        description: formData.get("description"),
        title: formData.get("title"),
        department: formData.get("department"),
        is_public: formData.get("access") === "open",
        ...(recurrenceGroupId ? { recurrence_group_id: recurrenceGroupId } : {})
    };
    const additionalReservationData = selectedTimeRanges.slice(1).map(timeRange => {
        const additionalReservation = {
            ...reservationData,
            id: undefined,
            start_time: timeRange.start.toISOString(),
            end_time: timeRange.end.toISOString()
        };

        delete additionalReservation.id;
        return additionalReservation;
    });

    const validation = validateEditedReservations({
        reservationsToSave: [
            reservationData,
            ...additionalReservationData
        ],
        existingReservations: reservations,
        selectedReservation,
        users
    });

    if (!validation.isValid) {
        setAdminReservationStatus(validation.message, "error");
        return;
    }

    setAdminReservationStatus("");
    setAdminReservationSubmitting(true);

    try {
        const updatedReservation = await updateEvent(reservationData);
        const reservationToRender = updatedReservation || reservationData;
        const createdReservations = await createEvents(additionalReservationData, {
            existingReservations: reservations,
            users
        });

        reservations = reservations.map(reservation => {
            if (String(reservation.id) === String(reservationToRender.id)) {
                return reservationToRender;
            }

            return reservation;
        });
        reservations.push(...createdReservations);

        renderUserDetails();
        await adminReservationDraftAutosave.discard();
        closeReservationEditModal({ flushDraft: false });
    } catch (error) {
        console.error("Could not update reservation:", error);
        setAdminReservationStatus(
            error.message || "Could not save reservation. Please try again.",
            "error"
        );
    } finally {
        setAdminReservationSubmitting(false);
    }
});

function validateEditedReservations({
    reservationsToSave,
    existingReservations,
    selectedReservation,
    users
}) {
    const selectedReservationId = String(selectedReservation?.id ?? "");
    const reservationsToCheck = existingReservations.filter(reservation => {
        return String(reservation.id) !== selectedReservationId;
    });

    for (const [index, reservationData] of reservationsToSave.entries()) {
        const validation = validateReservationData({
            reservationData,
            existingReservations: reservationsToCheck,
            users,
            requireId: index === 0
        });

        if (!validation.isValid) {
            return validation;
        }

        reservationsToCheck.push(reservationData);
    }

    return {
        isValid: true,
        message: ""
    };
}

async function handleAdminReservationDelete() {
    if (!selectedReservation) {
        return;
    }

    const reservationTitle = selectedReservation.title || "this reservation";
    const shouldDelete = window.confirm(
        `Delete "${reservationTitle}"? This cannot be undone.`
    );

    if (!shouldDelete) {
        return;
    }

    setAdminReservationStatus("");
    setAdminReservationDeleting(true);

    try {
        await deleteEvent(selectedReservation);
        await adminReservationDraftAutosave.discard();
        removeReservationsFromAdminState([selectedReservation]);

        renderUserDetails();
        closeReservationEditModal({ flushDraft: false });
    } catch (error) {
        console.error("Could not delete reservation:", error);
        setAdminReservationStatus(
            error.message || "Could not delete reservation. Please try again.",
            "error"
        );
    } finally {
        setAdminReservationDeleting(false);
    }
}

async function handleAdminReservationDeleteSeries() {
    if (!selectedReservation) {
        return;
    }

    const seriesReservations = getReservationSeries(selectedReservation, reservations);

    if (seriesReservations.length < 2) {
        return;
    }

    const reservationTitle = selectedReservation.title || "this recurring reservation";
    const shouldDelete = window.confirm(
        `Delete all ${seriesReservations.length} reservations in "${reservationTitle}"? This cannot be undone.`
    );

    if (!shouldDelete) {
        return;
    }

    setAdminReservationStatus("");
    setAdminReservationDeleting(true, "series");

    try {
        await deleteReservations(seriesReservations);
        await adminReservationDraftAutosave.discard();
        removeReservationsFromAdminState(seriesReservations);

        renderUserDetails();
        closeReservationEditModal({ flushDraft: false });
    } catch (error) {
        console.error("Could not delete recurring reservation series:", error);
        setAdminReservationStatus(
            error.message || "Could not delete recurring reservation series. Please try again.",
            "error"
        );
    } finally {
        setAdminReservationDeleting(false);
    }
}

function getReservationSeries(reservation, reservationList) {
    const recurrenceGroupId = reservation?.recurrence_group_id;

    if (!recurrenceGroupId) {
        return [];
    }

    return reservationList.filter(candidate => {
        return candidate.recurrence_group_id === recurrenceGroupId;
    });
}

async function deleteReservations(reservationsToDelete) {
    for (const reservation of reservationsToDelete) {
        await deleteEvent(reservation);
    }
}

function removeReservationsFromAdminState(reservationsToRemove) {
    const deletedReservationIds = new Set(
        reservationsToRemove.map(reservation => String(reservation.id))
    );

    reservations = reservations.filter(reservation => {
        return !deletedReservationIds.has(String(reservation.id));
    });

    attendees = attendees.filter(attendee => {
        return !deletedReservationIds.has(String(attendee.event_id));
    });
}

async function handleConfirmUserStatusChange() {
    if (!userPendingStatusChange) {
        return;
    }

    const userToUpdate = userPendingStatusChange;
    const disablingUser = pendingStatusAction === "disable";
    const actionLabel = disablingUser ? "Disable User" : "Enable User";

    disableUserConfirmBtn.disabled = true;
    disableUserConfirmBtn.textContent = disablingUser ? "Disabling..." : "Enabling...";

    try {
        if (disablingUser) {
            await disableUser({ id: userToUpdate.id });
        } else {
            await enableUser({ id: userToUpdate.id });
        }
    } catch (error) {
        console.error("Could not update user status:", error);
        disableUserWarning.textContent = getUserFormErrorMessage(
            error,
            `Could not ${disablingUser ? "disable" : "enable"} the user. Please try again.`
        );
        disableUserConfirmBtn.disabled = false;
        disableUserConfirmBtn.textContent = actionLabel;
        return;
    }

    users = users.map(user => {
        if (String(user.id) === String(userToUpdate.id)) {
            const updatedUser = {
                ...user,
                enabled: !disablingUser
            };

            selectedUser = updatedUser;

            return updatedUser;
        }

        return user;
    });

    userPendingStatusChange = null;

    renderUsers();
    renderUserDetails();
    closeDisableUserModal();
}

userSearchInput.addEventListener("input", event => {
    userSearchTerm = event.target.value;
    renderUsers();
});

userRoleFilter.addEventListener("change", event => {
    userRoleValue = event.target.value;
    renderUsers();
});

userSortSelect.addEventListener("change", event => {
    userSortValue = event.target.value;
    renderUsers();
});

userModalCloseBtn.addEventListener("click", closeUserModal);
userCancelBtn.addEventListener("click", closeUserModal);

disableUserModalCloseBtn.addEventListener("click", closeDisableUserModal);
disableUserCancelBtn.addEventListener("click", closeDisableUserModal);
disableUserConfirmBtn.addEventListener("click", handleConfirmUserStatusChange);

adminReservationModalCloseBtn.addEventListener("click", closeReservationEditModal);
adminReservationCancelBtn.addEventListener("click", closeReservationEditModal);
adminReservationDeleteBtn.addEventListener("click", handleAdminReservationDelete);
adminReservationDeleteSeriesBtn.addEventListener("click", handleAdminReservationDeleteSeries);

bindBackdropClose(userModalOverlay, closeUserModal);
bindBackdropClose(disableUserModalOverlay, closeDisableUserModal);
bindBackdropClose(adminReservationModalOverlay, closeReservationEditModal);

document.addEventListener("keydown", event => {
    if (
        event.key === "Escape" &&
        userModalOverlay.classList.contains("active")
    ) {
        closeUserModal();
    }

    if (
        event.key === "Escape" &&
        disableUserModalOverlay.classList.contains("active")
    ) {
        closeDisableUserModal();
    }

    if (
        event.key === "Escape" &&
        adminReservationModalOverlay.classList.contains("active")
    ) {
        closeReservationEditModal();
    }
});

logoutBtn.addEventListener("click", async () => {
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
});

bootstrapAdminDashboard();
