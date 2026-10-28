/*
 * Taska consent page (Jira TAS-414, TAS-445), /consent on oauth-server.
 *
 * Reads `state` from this page's address, asks GET /oauth/consent?state= what
 * the app is requesting, and draws one card from the page's templates:
 *   200 → the request; 401 → /login; 404, 403, other 4xx → T1 (no longer
 *   valid); network, no answer within 15 s, 5xx or an answer it cannot read
 *   (a missing field, or a `redirect_kind` other than "web" or "loopback")
 *   → T3. The page and the server ship in one jar, so a field it does not
 *   know is a fault to fail closed on, not a variant to guess at.
 * Then: Deny / Allow post natively to submit_uri, a second submit is refused,
 * and a page restored from the back-forward cache asks again (its state is
 * single-use, so the answer is usually T1). A card drawn over one that held
 * focus takes focus on its title.
 *
 * CSP: no inline anything, no style writes, no innerHTML — text goes in with
 * textContent, elements come from <template>s or createElement.
 */
(() => {
  "use strict";

  const wrap = document.getElementById("wrap");
  let card = document.getElementById("card");
  let pending = false;

  const READ = "See projects, issues, comments and work logs.";
  const WRITE = "Also create and change issues, comment, move issues through the workflow and log time.";
  const NAME_LIMIT = 60;
  const TIMEOUT_MS = 15000;
  const KINDS = new Set(["web", "loopback"]);

  // Sign-in keeps the typed email for one refusal; past sign-in it is not
  // needed, so it does not outlive the step.
  try {
    window.sessionStorage.removeItem("taska.oauth.email");
  } catch {
    // Storage refused: there is nothing kept to remove.
  }

  const isText = (value) => typeof value === "string" && value.length > 0;

  /** Strips bidi controls (U+202A–202E, U+2066–2069), collapses whitespace
      and cuts the name at 60 code points + "…". The server does not clean
      client names yet (asked in TAS-445); once it does, this stays and is a
      no-op on what it sends. */
  function cleanName(raw) {
    if (typeof raw !== "string") return "";
    const name = raw.replace(/[\u202A-\u202E\u2066-\u2069]/g, "").replace(/\s+/g, " ").trim();
    const points = Array.from(name);
    return points.length > NAME_LIMIT + 1 ? `${points.slice(0, NAME_LIMIT).join("").trimEnd()}…` : name;
  }

  /** The destination host as the server sent it (the redirect URI's host,
      ASCII A-label, `:port` only when the URI has one, IPv6 in brackets),
      lowercased. Anything that is not a plain host[:port] or [v6][:port] is
      refused rather than printed. */
  function cleanHost(raw) {
    if (!isText(raw)) return null;
    const host = raw.toLowerCase();
    return /^(?:[a-z0-9_-]+(?:\.[a-z0-9_-]+)*\.?|\[[0-9a-f:.]+\])(?::\d{1,5})?$/.test(host) ? host : null;
  }

  /** The answer, checked; null when it cannot be drawn honestly. */
  function readRequest(body) {
    if (!body || typeof body !== "object") return null;
    const { client_id, state, submit_uri, csrf_parameter, csrf_token, scopes, granted_scopes } = body;
    if (![client_id, state, submit_uri, csrf_parameter, csrf_token].every(isText)) return null;
    // Root-relative only: no other origin, no protocol-relative URL.
    if (!submit_uri.startsWith("/") || submit_uri.startsWith("//") || submit_uri.includes("\\")) return null;
    if (!/^[A-Za-z0-9_.-]{1,64}$/.test(csrf_parameter)) return null;
    if (!Array.isArray(scopes)) return null;
    if (granted_scopes !== undefined && !Array.isArray(granted_scopes)) return null;
    const host = cleanHost(body.client_host);
    if (host === null) return null;
    // Whether access goes to a website or to an app on this device is the
    // server's word, never read off the host.
    if (!KINDS.has(body.redirect_kind)) return null;
    const requested = new Set(scopes.filter((scope) => typeof scope === "string"));
    const granted = new Set((granted_scopes ?? []).filter((scope) => typeof scope === "string"));
    // A scope this page cannot describe is never printed and never posted;
    // the server refuses those before consent. Nothing describable at all is
    // a request this page cannot show.
    if (!requested.has("read") && !requested.has("write")) return null;
    const account = body.account && typeof body.account === "object" && isText(body.account.email) ? body.account.email : null;
    return {
      clientId: client_id,
      state,
      submitUri: submit_uri,
      csrfParameter: csrf_parameter,
      csrfToken: csrf_token,
      name: cleanName(body.client_name),
      verified: isText(body.metadata_host),
      host,
      device: body.redirect_kind === "loopback",
      read: requested.has("read"),
      write: requested.has("write"),
      writeGranted: granted.has("write"),
      offline: requested.has("offline_access"),
      account,
    };
  }

  const slot = (root, name) => root.querySelector(`[data-slot="${name}"]`);

  function hostSpan(host) {
    const span = document.createElement("span");
    span.className = "consent-host";
    span.translate = false;
    span.textContent = host;
    return span;
  }

  function nameBdi(name) {
    const bdi = document.createElement("bdi");
    bdi.translate = false;
    bdi.textContent = name;
    return bdi;
  }

  /** Swaps the card in. If the one it replaces held focus (a page back from
      the back-forward cache, say), focus would fall to <body>; it goes to
      the new card's title instead, so the change is read out. */
  function place(fresh) {
    const hadFocus = card.contains(document.activeElement);
    card.replaceWith(fresh);
    card = fresh;
    wrap.hidden = false;
    if (hadFocus) {
      const title = fresh.querySelector("h1");
      title.tabIndex = -1;
      title.focus();
    }
  }

  /** T1 or T3, with the request id and the OAuth error code when there are any. */
  function showTerminal(kind, facts) {
    const fresh = document.getElementById(kind === "expired" ? "card-expired" : "card-unavailable").content.firstElementChild.cloneNode(true);
    const parts = [];
    if (facts && facts.code) parts.push(`Code: ${facts.code}`);
    if (facts && facts.requestId) parts.push(`Request ID: ${facts.requestId}`);
    if (parts.length > 0) {
      const detail = document.createElement("p");
      detail.className = "auth-detail";
      for (const part of parts) {
        const span = document.createElement("span");
        span.textContent = part;
        detail.append(span);
      }
      fresh.append(detail);
    }
    document.title = kind === "expired" ? "Request no longer valid · Taska" : "Connection failed · Taska";
    place(fresh);
  }

  function showRequest(request) {
    const fresh = document.getElementById("card-consent").content.firstElementChild.cloneNode(true);

    // Who.
    const title = slot(fresh, "title");
    if (request.name === "") {
      title.textContent = "An app wants to access your Taska account";
    } else if (request.verified) {
      title.append(nameBdi(request.name), " wants to access your Taska account");
    } else {
      title.append("An app named “", nameBdi(request.name), "” wants to access your Taska account");
    }

    // Where.
    const destination = slot(fresh, "destination");
    destination.append(
      request.device ? "If you allow, access goes to an app on this device, at " : "If you allow, access goes to the app at ",
      hostSpan(request.host),
      ".",
    );

    // Caveat (DCR only).
    const unverified = slot(fresh, "unverified");
    const allow = slot(fresh, "allow");
    if (request.verified) {
      unverified.remove();
      allow.setAttribute("aria-describedby", "consent-destination");
    } else if (request.device) {
      unverified.textContent =
        "Taska has not verified this app — its name is set by whoever made it. Allow only if you just started connecting it on this device.";
    } else {
      unverified.append(
        "Taska has not verified this app — its name is set by whoever made it. Allow only if you trust ",
        hostSpan(request.host),
        ".",
      );
    }

    // What: (c) a choice when write is asked for and not yet granted; (a) or
    // (b) a fixed level otherwise.
    const choice = request.read && request.write && !request.writeGranted;
    if (choice) {
      slot(fresh, "access-fixed").remove();
    } else {
      slot(fresh, "access-choice").remove();
      slot(fresh, "access-title").textContent = request.write ? "Read and write" : "Read";
      slot(fresh, "access-description").textContent = request.write ? `${READ} ${WRITE}` : READ;
    }
    if (!request.offline) slot(fresh, "offline").remove();

    // The decision.
    const form = fresh.querySelector("#consent-form");
    form.setAttribute("action", request.submitUri);
    slot(fresh, "client-id").value = request.clientId;
    slot(fresh, "state").value = request.state;
    const csrf = slot(fresh, "csrf");
    csrf.name = request.csrfParameter;
    csrf.value = request.csrfToken;

    // As whom.
    const switchCsrf = slot(fresh, "switch-csrf");
    switchCsrf.name = request.csrfParameter;
    switchCsrf.value = request.csrfToken;
    slot(fresh, "switch-state").value = request.state;
    if (request.account) slot(fresh, "account-email").textContent = request.account;
    else slot(fresh, "account").remove();

    form.addEventListener("submit", (event) => {
      if (pending || !event.submitter) {
        event.preventDefault();
        return;
      }
      const allowing = event.submitter.value === "allow";
      // Deny posts no scope at all; Allow posts what was chosen.
      const write = request.write && (!choice || fresh.querySelector('input[name="access"][value="read-write"]').checked);
      slot(fresh, "scope-read").disabled = !(allowing && request.read);
      slot(fresh, "scope-write").disabled = !(allowing && write);
      slot(fresh, "scope-offline").disabled = !(allowing && request.offline);
      markPending();
    });
    fresh.querySelector("#switch-account").addEventListener("submit", (event) => {
      if (pending) {
        event.preventDefault();
        return;
      }
      markPending();
    });

    document.title = "Connect an app · Taska";
    place(fresh);
  }

  /** In flight: every submit button says so and refuses a second press;
      never `disabled`, or the pressed button's decision would not post. */
  function markPending() {
    pending = true;
    for (const button of document.querySelectorAll('button[type="submit"]')) button.setAttribute("aria-disabled", "true");
  }

  function clearPending() {
    pending = false;
    for (const button of document.querySelectorAll('button[type="submit"]')) button.removeAttribute("aria-disabled");
  }

  function requestIdOf(response) {
    const id = response.headers.get("X-Request-Id");
    return id && /^[A-Za-z0-9._:-]{1,128}$/.test(id) ? id : null;
  }

  async function codeOf(response) {
    try {
      const body = await response.json();
      return body && typeof body.error === "string" && /^[a-z_]{1,64}$/.test(body.error) ? body.error : null;
    } catch {
      return null;
    }
  }

  async function load() {
    const state = new URLSearchParams(window.location.search).get("state");
    if (!state) {
      showTerminal("expired");
      return;
    }
    let response;
    try {
      // The timeout covers the body too: a read that stalls ends as T3.
      response = await fetch(`/oauth/consent?state=${encodeURIComponent(state)}`, {
        credentials: "same-origin",
        headers: { Accept: "application/json" },
        cache: "no-store",
        signal: AbortSignal.timeout(TIMEOUT_MS),
      });
    } catch {
      showTerminal("unavailable");
      return;
    }
    if (response.status === 401) {
      window.location.replace("/login");
      return;
    }
    const requestId = requestIdOf(response);
    if (response.status >= 400 && response.status < 500) {
      showTerminal("expired", { code: await codeOf(response), requestId });
      return;
    }
    if (!response.ok) {
      showTerminal("unavailable", { code: await codeOf(response), requestId });
      return;
    }
    let request = null;
    try {
      request = readRequest(await response.json());
    } catch {
      request = null;
    }
    if (request === null) showTerminal("unavailable", { requestId });
    else showRequest(request);
  }

  // Back to this page from the app or from /login: the state was used or may
  // have been, so ask again rather than offer a form the server will refuse.
  window.addEventListener("pageshow", (event) => {
    if (!event.persisted) return;
    clearPending();
    load();
  });

  load();
})();
