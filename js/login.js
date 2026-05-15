const loginBtn = document.getElementById("loginBtn");

const email = document.getElementById("email");
const password = document.getElementById("password");

const message = document.getElementById("message");

loginBtn.addEventListener("click", function () {
  const username = email.value.trim();
  const userPassword = password.value.trim();

  if (username === "" || userPassword === "") {
    message.textContent =
      "Please fill in both fields.";

    return;
  }

  // Admin login redirects to admin page
  if (
    username === "admin" &&
    userPassword === "admin"
  ) {
    sessionStorage.setItem(
      "adminLoggedIn",
      "true"
    );

    sessionStorage.setItem(
      "facultyLoggedIn",
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

  // Standard login redirects to index and enables create reservation button
  sessionStorage.setItem(
    "facultyLoggedIn",
    "true"
  );

  message.textContent =
    "Login successful!";

  setTimeout(() => {
    window.location.href =
      "index.html";
  }, 500);
});