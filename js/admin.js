import {
    getEvents,
    getUsers,
    createUser,
    updateUser,
    updateEvent,
    disableUser,
    enableUser
} from "./api.js";
import {
    formatReadableDate,
    getEventColorClass
} from "./utils/dateUtils.js";

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
const adminReservationForm = document.getElementById("adminReservationForm");
const adminReservationSubmitBtn = document.getElementById("adminReservationSubmitBtn");

let users = [];
let reservations = [];
let selectedUser = null;
let selectedReservation = null;
let userPendingStatusChange = null;
let pendingStatusAction = "disable";
let modalMode = "create";
let userSearchTerm = "";
let userRoleValue = "all";
let userSortValue = "name-asc";

const isAdminLoggedIn =
    sessionStorage.getItem("adminLoggedIn") === "true";

if (!isAdminLoggedIn) {
    window.location.href = "login.html";
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

        if (user.disabled) {
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
        role.textContent = user.disabled ? "disabled" : getUserRoleName(user);

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
        disableButton.className = selectedUser.disabled
            ? "reservation-secondary-btn enable-user-btn"
            : "reservation-secondary-btn disable-user-btn";
        disableButton.textContent = selectedUser.disabled ? "Enable User" : "Disable User";
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
    const reservationItem = document.createElement("button");
    reservationItem.type = "button";
    reservationItem.className = `
        upcoming-event-card
        selected-user-reservation-card
        ${getEventColorClass(reservation.event_type)}
    `;

    const reservationTitle = document.createElement("div");
    reservationTitle.className = "upcoming-event-title";
    reservationTitle.textContent = reservation.title || "Untitled reservation";

    const reservationDate = document.createElement("div");
    reservationDate.className = "upcoming-event-date";
    reservationDate.textContent = formatReservationDate(reservation);

    const reservationTime = document.createElement("div");
    reservationTime.className = "upcoming-event-time";
    reservationTime.textContent = formatReservationTime(reservation);

    reservationItem.append(reservationTitle, reservationDate, reservationTime);
    reservationItem.addEventListener("click", () => {
        openReservationEditModal(reservation);
    });

    return reservationItem;
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

function formatReservationRange(reservation) {
    if (!reservation.start_time || !reservation.end_time) {
        return "Time not set";
    }

    const startDate = new Date(reservation.start_time);
    const endDate = new Date(reservation.end_time);

    if (Number.isNaN(startDate.getTime()) || Number.isNaN(endDate.getTime())) {
        return "Time not set";
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

function formatReservationDate(reservation) {
    if (!reservation.start_time) {
        return "Date not set";
    }

    const startDate = new Date(reservation.start_time);

    if (Number.isNaN(startDate.getTime())) {
        return "Date not set";
    }

    return formatReadableDate(startDate);
}

function formatReservationTime(reservation) {
    if (!reservation.start_time || !reservation.end_time) {
        return "Time not set";
    }

    const startDate = new Date(reservation.start_time);
    const endDate = new Date(reservation.end_time);

    if (Number.isNaN(startDate.getTime()) || Number.isNaN(endDate.getTime())) {
        return "Time not set";
    }

    const startTime = startDate.toLocaleTimeString([], {
        hour: "numeric",
        minute: "2-digit"
    });
    const endTime = endDate.toLocaleTimeString([], {
        hour: "numeric",
        minute: "2-digit"
    });

    return `${startTime} - ${endTime}`;
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
            if (Boolean(firstUser.disabled) !== Boolean(secondUser.disabled)) {
                return firstUser.disabled ? 1 : -1;
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
    return user.role_name || user.role || "Faculty";
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
    pendingStatusAction = selectedUser.disabled ? "enable" : "disable";
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

function openReservationEditModal(reservation) {
    selectedReservation = reservation;

    document.getElementById("adminReservationId").value = reservation.id ?? "";
    document.getElementById("adminReservationHostUserId").value =
        reservation.host_user_id ?? reservation.host_user?.id ?? selectedUser?.id ?? "";
    document.getElementById("adminReservationTitle").value = reservation.title ?? "";
    document.getElementById("adminReservationType").value = reservation.event_type ?? "";
    document.getElementById("adminReservationDescription").value = reservation.description ?? "";
    document.getElementById("adminReservationStart").value = formatDateTimeLocalValue(reservation.start_time);
    document.getElementById("adminReservationEnd").value = formatDateTimeLocalValue(reservation.end_time);
    document.getElementById("adminReservationDepartment").value = reservation.department ?? "";
    document.getElementById("adminReservationHost").value = getReservationHostName(reservation);

    const accessValue = reservation.is_public ? "open" : "private";
    const accessInput = adminReservationForm.querySelector(
        `input[name="access"][value="${accessValue}"]`
    );

    if (accessInput) {
        accessInput.checked = true;
    }

    adminReservationSubmitBtn.textContent = "Save Changes";
    adminReservationSubmitBtn.disabled = false;
    adminReservationModalOverlay.classList.add("active");
    adminReservationModalOverlay.setAttribute("aria-hidden", "false");
}

function closeReservationEditModal() {
    adminReservationModalOverlay.classList.remove("active");
    adminReservationModalOverlay.setAttribute("aria-hidden", "true");

    selectedReservation = null;
    adminReservationForm.reset();
    adminReservationSubmitBtn.textContent = "Save Changes";
    adminReservationSubmitBtn.disabled = false;
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
        role_name: fallbackHostUser?.role_name ?? getUserRoleName(selectedUser || {})
    };
}

userForm.addEventListener("submit", async event => {
    event.preventDefault();

    const formData = new FormData(userForm);
    const userData = Object.fromEntries(formData.entries());

    if (modalMode === "create") {
        delete userData.id;

        try {
            const createdUser = await createUser(userData);
            users.push(createdUser);
        } catch (error) {
            console.error("Could not create user on backend. Adding locally:", error);

            users.push({
                ...userData,
                id: Date.now()
            });
        }
    }

    if (modalMode === "edit") {
        try {
            await updateUser(userData);
        } catch (error) {
            console.error("Could not update user on backend. Updating locally:", error);
        }

        users = users.map(user => {
            if (String(user.id) === String(userData.id)) {
                const updatedUser = {
                    ...user,
                    ...userData
                };

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

createUserBtn.addEventListener("click", openCreateUserModal);

adminReservationForm.addEventListener("submit", async event => {
    event.preventDefault();

    if (!selectedReservation) {
        return;
    }

    const formData = new FormData(adminReservationForm);
    const hostUserId = formData.get("host_user_id") || selectedUser?.id;
    const startTime = new Date(formData.get("start")).toISOString();
    const endTime = new Date(formData.get("end")).toISOString();

    const reservationData = {
        ...selectedReservation,
        id: formData.get("id"),
        host_user_id: hostUserId,
        host_user: getUpdatedHostUser(
            formData.get("host") || "",
            selectedReservation.host_user
        ),
        start_time: startTime,
        end_time: endTime,
        event_type: formData.get("type"),
        description: formData.get("description"),
        title: formData.get("title"),
        department: formData.get("department"),
        is_public: formData.get("access") === "open"
    };

    adminReservationSubmitBtn.disabled = true;
    adminReservationSubmitBtn.textContent = "Saving...";

    try {
        await updateEvent(reservationData);
    } catch (error) {
        console.error("Could not update reservation on backend. Updating locally:", error);
    }

    reservations = reservations.map(reservation => {
        if (String(reservation.id) === String(reservationData.id)) {
            return reservationData;
        }

        return reservation;
    });

    renderUserDetails();
    closeReservationEditModal();
});

async function handleConfirmUserStatusChange() {
    if (!userPendingStatusChange) {
        return;
    }

    const userToUpdate = userPendingStatusChange;
    const disablingUser = pendingStatusAction === "disable";

    disableUserConfirmBtn.disabled = true;
    disableUserConfirmBtn.textContent = disablingUser ? "Disabling..." : "Enabling...";

    try {
        if (disablingUser) {
            await disableUser({ id: userToUpdate.id });
        } else {
            await enableUser({ id: userToUpdate.id });
        }
    } catch (error) {
        console.error("Could not update user status on backend. Updating locally:", error);
    }

    users = users.map(user => {
        if (String(user.id) === String(userToUpdate.id)) {
            const updatedUser = {
                ...user,
                disabled: disablingUser
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

logoutBtn.addEventListener("click", () => {
    sessionStorage.removeItem("adminLoggedIn");
    sessionStorage.removeItem("facultyLoggedIn");
    sessionStorage.removeItem("currentUserId");
    sessionStorage.removeItem("currentUserEmail");
    sessionStorage.removeItem("currentUserRole");

    window.location.href = "index.html";
});

loadDashboardData();
