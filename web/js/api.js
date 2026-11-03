(function () {
  const apiBase = window.location.port === "5500"
    ? window.location.protocol + "//" + window.location.hostname + ":8080"
    : "";

  async function request(path, options) {
    const settings = options || {};
    let response;
    try {
      response = await fetch(apiBase + path, {
        method: settings.method || "GET",
        credentials: "include",
        headers: settings.body === undefined
          ? {}
          : { "Content-Type": "application/json" },
        body: settings.body === undefined ? undefined : JSON.stringify(settings.body),
      });
    } catch (error) {
      throw new Error(
        "Cannot reach the SmartTask backend. Start it with .\\backend\\run.ps1 and retry.",
      );
    }

    let result = {};
    const contentType = response.headers.get("content-type") || "";
    if (contentType.includes("application/json")) {
      result = await response.json();
    }

    if (!response.ok) {
      const error = new Error(result.error || "The request could not be completed.");
      error.status = response.status;
      throw error;
    }

    return result;
  }

  window.SmartTaskApi = { request };
})();
