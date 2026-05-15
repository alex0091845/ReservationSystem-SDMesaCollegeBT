// Opens reservation creation modal
function openEditUserModal() {
    editUserModalOverlay.classList.add("active");
    editUserModalOverlay.setAttribute("aria-hidden", "false");
}

// Open modal button
if (createUserBtn) {
    createUserBtn.addEventListener(
        "click",
        openEditUserModal
    );
}