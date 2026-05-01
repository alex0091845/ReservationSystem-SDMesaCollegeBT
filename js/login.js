const loginBtn = document.getElementById("loginBtn");
const email = document.getElementById("email");
const password = document.getElementById("password");
const message = document.getElementById("message");

loginBtn.addEventListener("click", function () {
  if (email.value === "" || password.value === "") {
    message.textContent = "Please fill in both fields.";
  } else {
    message.textContent = "Login submitted!";
  }
});