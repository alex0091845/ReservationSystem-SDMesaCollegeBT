import { getCurrentSession, getUsers, loginUser } from "./api.js";

const loginBtn = document.getElementById("loginBtn");

const email = document.getElementById("email");
const password = document.getElementById("password");
const showPassword = document.getElementById("showPassword");

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
    const loginResponse = await loginUser(username, userPassword);
    const matchedUser = getAuthenticatedUser(loginResponse) ||
      getAuthenticatedUser(await getCurrentSession());

    if (!matchedUser) {
      message.textContent =
        "Invalid email or password.";

      return;
    }

    completeLogin(matchedUser);
  } catch (error) {
    console.error("Login failed:", error);

    if (!isServerUnavailableError(error)) {
      message.textContent =
        "Email or Password is incorrect. Please try again.";

      return;
    }

    await handleMockLoginFallback(username, userPassword);
  }
}

async function handleMockLoginFallback(username, userPassword) {
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

    completeLogin(matchedUser, "Offline login successful!");
  } catch (error) {
    console.error("Offline login fallback failed:", error);

    message.textContent =
      "Could not log in. Please try again.";
  }
}

function completeLogin(matchedUser, successMessage) {
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
    getUserRoleName(matchedUser)
  );

  const isAdmin =
    getUserRoleName(matchedUser).toLowerCase() === "admin";

  if (isAdmin) {
    sessionStorage.setItem(
      "adminLoggedIn",
      "true"
    );

    message.textContent =
      successMessage || "Admin login successful!";

    setTimeout(() => {
      window.location.href =
        "admin.html";
    }, 500);

    return;
  }

  sessionStorage.removeItem("adminLoggedIn");

  message.textContent =
    successMessage || "Login successful!";

  setTimeout(() => {
    window.location.href =
      "index.html";
  }, 500);
}

function isServerUnavailableError(error) {
  return (
    error?.name === "AbortError" ||
    error?.message?.includes("Failed to fetch") ||
    error?.message?.includes("NetworkError") ||
    error?.message?.includes("signal is aborted")
  );
}

function getAuthenticatedUser(authResponse) {
    if (!authResponse) {
        return null;
    }

    const user = authResponse.user || authResponse;

    if (!user.id && !user.email && !getUserRoleName(user)) {
        return null;
    }

    return user;
}

function getUserRoleName(user) {
  return user.role_name || user.role || "";
}

loginBtn.addEventListener("click", handleLogin);

if (showPassword) {
  showPassword.addEventListener("change", () => {
    setPasswordVisibility(showPassword.checked);
  });
}

function setPasswordVisibility(shouldShowPassword) {
  password.type = shouldShowPassword ? "text" : "password";
}

loginFields.forEach(field => {
  field.addEventListener("keydown", event => {
    if (event.key === "Enter") {
      event.preventDefault();
      handleLogin();
    }
  });
});
