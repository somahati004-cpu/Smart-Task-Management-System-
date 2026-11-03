const api = window.SmartTaskApi;
const loginForm = document.getElementById("loginForm");
const registerForm = document.getElementById("registerForm");

function showMessage(message, isError) {
  const element = document.getElementById("message");
  element.textContent = message;
  element.style.color = isError ? "#F87171" : "#22D3EE";
}

if (loginForm) {
  loginForm.addEventListener("submit", async function (event) {
    event.preventDefault();
    showMessage("", false);

    try {
      await api.request("/api/auth/login", {
        method: "POST",
        body: {
          username: document.getElementById("username").value.trim(),
          password: document.getElementById("password").value,
        },
      });
      window.location.href = "dashboard.html";
    } catch (error) {
      showMessage(error.message, true);
    }
  });
}

if (registerForm) {
  registerForm.addEventListener("submit", async function (event) {
    event.preventDefault();
    showMessage("", false);

    const password = document.getElementById("password").value;
    const confirmPassword = document.getElementById("confirmPassword").value;
    if (password !== confirmPassword) {
      showMessage("Passwords do not match.", true);
      return;
    }
    if (password.length < 8) {
      showMessage("Password must be at least 8 characters.", true);
      return;
    }

    try {
      await api.request("/api/auth/register", {
        method: "POST",
        body: {
          fullName: document.getElementById("fullname").value.trim(),
          email: document.getElementById("email").value.trim(),
          username: document.getElementById("username").value.trim(),
          password: password,
        },
      });
      window.location.href = "dashboard.html";
    } catch (error) {
      showMessage(error.message, true);
    }
  });
}
