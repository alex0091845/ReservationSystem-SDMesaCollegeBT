import { getUsers } from "./api.js";

const loginBtn = document.getElementById("loginBtn");

const email = document.getElementById("email");
const password = document.getElementById("password");

const loginFields = document.querySelectorAll(".login-field");

const message = document.getElementById("message");

async function handleLogin() {
  const username = email.value.trim().toLowerCase();
  const userPassword = password.value.trim();

  if (username === "" || userPassword === "") {
    message.textContent =
      "Please fill in both fields.";

    return;
  }

  try {
    const users = await getUsers();
    const matchedUser = users.find(user => {
      return (
        user.email.toLowerCase() === username &&
        user.password_hash === userPassword
      );
    });

    if (!matchedUser) {
      message.textContent =
        "Invalid email or password.";

      return;
    }

    sessionStorage.setItem(
      "facultyLoggedIn",
      "true"
    );

    sessionStorage.setItem(
      "currentUserId",
      String(matchedUser.id)
    );

    sessionStorage.setItem(
      "currentUserEmail",
      matchedUser.email
    );

    sessionStorage.setItem(
      "currentUserRole",
      matchedUser.role_name
    );

    const isAdmin =
      matchedUser.role_name.toLowerCase() === "admin";

    if (isAdmin) {
      sessionStorage.setItem(
        "adminLoggedIn",
        "true"
      );

      message.textContent =
        "Admin login successful!";

      setTimeout(() => {
        window.location.href =
          "admin.html";
      }, 500);

      return;
    }

    sessionStorage.removeItem("adminLoggedIn");

    message.textContent =
      "Login successful!";

    setTimeout(() => {
      window.location.href =
        "index.html";
    }, 500);
  } catch (error) {
    console.error("Login failed:", error);

    message.textContent =
      "Could not log in. Please try again.";
  }
}

loginBtn.addEventListener("click", handleLogin);

loginFields.forEach(field => {
  field.addEventListener("keydown", event => {
    if (event.key === "Enter") {
      event.preventDefault();
      handleLogin();
    }
  });
});
