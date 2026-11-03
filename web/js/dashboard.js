const api = window.SmartTaskApi;
const addWishBtn = document.getElementById("addWishBtn");
const wishForm = document.getElementById("wishForm");
const cancelBtn = document.getElementById("cancelBtn");
const wishList = document.getElementById("wishList");
const wishTitle = document.getElementById("wishTitle");
const wishDescription = document.getElementById("wishDescription");
const searchWish = document.getElementById("searchWish");
const filterWish = document.getElementById("filterWish");
const sortWish = document.getElementById("sortWish");
const dashboardMessage = document.getElementById("dashboardMessage");

let wishes = [];
let editingId = null;

function showError(error) {
  if (error.status === 401) {
    window.location.href = "login.html";
    return;
  }
  dashboardMessage.textContent = error.message || "The request could not be completed.";
}

function clearMessage() {
  dashboardMessage.textContent = "";
}

addWishBtn.addEventListener("click", function () {
  editingId = null;
  wishForm.reset();
  document.getElementById("formTitle").textContent = "Add New Wish";
  wishForm.hidden = false;
  wishTitle.focus();
});

document.getElementById("addWishBtnSecondary").addEventListener("click", function () {
  addWishBtn.click();
});

cancelBtn.addEventListener("click", function () {
  wishForm.reset();
  wishForm.hidden = true;
  editingId = null;
});

wishForm.addEventListener("submit", async function (event) {
  event.preventDefault();
  const payload = {
    title: wishTitle.value.trim(),
    description: wishDescription.value.trim(),
  };
  if (!payload.title) {
    dashboardMessage.textContent = "Please enter a wish title.";
    return;
  }

  try {
    clearMessage();
    if (editingId === null) {
      const wish = await api.request("/api/wishes", {
        method: "POST",
        body: payload,
      });
      wishes.unshift(wish);
    } else {
      const wish = await api.request("/api/wishes/" + encodeURIComponent(editingId), {
        method: "PUT",
        body: payload,
      });
      wishes = wishes.map(function (item) {
        return item.id === wish.id ? wish : item;
      });
    }
    wishForm.reset();
    wishForm.hidden = true;
    editingId = null;
    displayWishes();
  } catch (error) {
    showError(error);
  }
});

function displayWishes() {
  wishList.replaceChildren();
  const searchText = searchWish.value.toLowerCase().trim();
  const filterValue = filterWish.value;
  const sortValue = sortWish.value;

  const filteredWishes = wishes.filter(function (wish) {
    const matchesSearch =
      wish.title.toLowerCase().includes(searchText) ||
      (wish.description || "").toLowerCase().includes(searchText);
    const matchesFilter =
      filterValue === "all" ||
      (filterValue === "read" && wish.isRead) ||
      (filterValue === "pending" && !wish.isRead);
    return matchesSearch && matchesFilter;
  });

  filteredWishes.sort(function (a, b) {
    if (sortValue === "newest") {
      return Date.parse(b.createdAt || "") - Date.parse(a.createdAt || "");
    }
    if (sortValue === "oldest") {
      return Date.parse(a.createdAt || "") - Date.parse(b.createdAt || "");
    }
    if (sortValue === "az") {
      return a.title.localeCompare(b.title);
    }
    if (sortValue === "za") {
      return b.title.localeCompare(a.title);
    }
    return 0;
  });

  if (filteredWishes.length === 0) {
    const emptyMessage = document.createElement("p");
    emptyMessage.className = "empty-message";
    emptyMessage.textContent = "No matching wishes found.";
    wishList.appendChild(emptyMessage);
  }

  filteredWishes.forEach(function (wish) {
    const card = document.createElement("div");
    card.className = wish.isRead ? "wish-card read" : "wish-card";

    const title = document.createElement("h3");
    title.textContent = wish.title;

    const description = document.createElement("p");
    description.textContent = wish.description || "No description provided.";

    const actions = document.createElement("div");
    actions.className = "wish-actions";

    const editBtn = document.createElement("button");
    editBtn.className = "edit-btn";
    editBtn.textContent = "Edit";
    editBtn.addEventListener("click", function () {
      editWish(wish);
    });

    const readBtn = document.createElement("button");
    readBtn.className = "read-btn";
    readBtn.textContent = wish.isRead ? "Mark as Unread" : "Mark as Read";
    readBtn.addEventListener("click", async function () {
      try {
        clearMessage();
        const updatedWish = await api.request(
          "/api/wishes/" + encodeURIComponent(wish.id),
          {
            method: "PUT",
            body: { isRead: !wish.isRead },
          },
        );
        wishes = wishes.map(function (item) {
          return item.id === updatedWish.id ? updatedWish : item;
        });
        displayWishes();
      } catch (error) {
        showError(error);
      }
    });

    const deleteBtn = document.createElement("button");
    deleteBtn.className = "delete-btn";
    deleteBtn.textContent = "Delete";
    deleteBtn.addEventListener("click", async function () {
      if (!confirm("Are you sure you want to delete this wish?")) {
        return;
      }
      try {
        clearMessage();
        await api.request("/api/wishes/" + encodeURIComponent(wish.id), {
          method: "DELETE",
        });
        wishes = wishes.filter(function (item) {
          return item.id !== wish.id;
        });
        displayWishes();
      } catch (error) {
        showError(error);
      }
    });

    actions.append(editBtn, readBtn, deleteBtn);
    card.append(title, description, actions);
    wishList.appendChild(card);
  });

  updateStats();
}

function editWish(wish) {
  editingId = wish.id;
  wishTitle.value = wish.title;
  wishDescription.value = wish.description || "";
  document.getElementById("formTitle").textContent = "Edit Wish";
  wishForm.hidden = false;
  wishTitle.focus();
}

function updateStats() {
  const read = wishes.filter(function (wish) {
    return wish.isRead;
  }).length;
  document.getElementById("totalCount").textContent = wishes.length;
  document.getElementById("pendingCount").textContent = wishes.length - read;
  document.getElementById("readCount").textContent = read;
}

searchWish.addEventListener("input", displayWishes);
filterWish.addEventListener("change", displayWishes);
sortWish.addEventListener("change", displayWishes);

document.getElementById("logoutLink").addEventListener("click", async function (event) {
  event.preventDefault();
  try {
    await api.request("/api/auth/logout", { method: "POST" });
    window.location.href = "login.html";
  } catch (error) {
    showError(error);
  }
});

async function initializeDashboard() {
  try {
    const user = await api.request("/api/auth/me");
    document.getElementById("profileName").textContent = user.fullName;
    document.getElementById("profileAvatar").textContent =
      user.fullName.charAt(0).toUpperCase();
    wishes = await api.request("/api/wishes");
    displayWishes();
  } catch (error) {
    showError(error);
  }
}

initializeDashboard();
