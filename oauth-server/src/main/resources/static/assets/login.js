/*
 * Taska sign-in page (Jira TAS-414, TAS-445), /login on oauth-server.
 *
 * The page is static; its state comes from two places:
 *   - the backend's redirect after a refused POST /login:
 *       ?error=INVALID_CREDENTIALS            "Wrong email or password."
 *       ?error=ACCOUNT_LOCKED[&until=ISO]     the two locked lines
 *       ?error=SESSION_EXPIRED, ?done         T1, no form
 *       ?logout                               "You’re signed out of Taska", no form
 *       ?error=<anything else>                "Sign-in could not be finished."
 *     (the value itself is never printed);
 *   - GET /login/context → {csrf_parameter, csrf_token, pending_request}:
 *     the CSRF field for the form, and whether there is a request to sign in
 *     for at all (false → T1). Until it answers the submit is aria-disabled
 *     and refuses; if it fails or gives no answer within 15 s → T3.
 * Nothing is focused when the page opens: Email (Password after a wrong
 * password, Email after a lock) takes focus only once the context has
 * confirmed a pending request, and only if the reader has not put focus
 * somewhere themselves. «No pending request» and a failed context leave focus
 * on <body>, with no ring on a card that has nothing to press.
 * A terminal card drawn over the form while the form held focus (focus the
 * reader placed) takes focus on its title.
 * The email typed is kept in sessionStorage so a refusal can put it back (the
 * redirect does not carry it) and is removed once it has been put back, or on
 * any load that is not that refusal; the password never is kept.
 *
 * CSP: no inline anything, no style writes, no innerHTML.
 */
(() => {
  "use strict";

  const EMAIL_KEY = "taska.oauth.email";
  const TIMEOUT_MS = 15000;
  const TERMINAL = {
    expired: { template: "card-expired", title: "Request no longer valid · Taska" },
    unavailable: { template: "card-unavailable", title: "Connection failed · Taska" },
    "signed-out": { template: "card-signed-out", title: "Signed out · Taska" },
  };
  const ISO_INSTANT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$/;

  let card = document.getElementById("card");
  const form = document.getElementById("signin-form");
  const csrf = document.getElementById("signin-csrf");
  const email = document.getElementById("signin-email");
  const password = document.getElementById("signin-password");
  const toggle = document.getElementById("password-toggle");
  const submit = document.getElementById("signin-submit");
  const refusalBox = document.getElementById("signin-refusal-box");
  const refusal = document.getElementById("signin-refusal");
  const providerSection = document.getElementById("signin-providers");
  const providerLinks = document.getElementById("signin-provider-links");

  let ready = false; // the CSRF field is filled
  let pending = false; // the form has been sent
  let replaced = false; // a terminal card stands in for the form

  // ---------- Terminal cards ----------

  /** T1, T3 or signed out. If the form held focus, focus would fall to
      <body> with it; it goes to the new title instead, so the change is
      read out. On a card drawn before anything was focused it stays put. */
  function showTerminal(kind, requestId) {
    const { template, title } = TERMINAL[kind];
    const fresh = document.getElementById(template).content.firstElementChild.cloneNode(true);
    if (requestId) {
      const detail = document.createElement("p");
      detail.className = "auth-detail";
      const span = document.createElement("span");
      span.textContent = `Request ID: ${requestId}`;
      detail.append(span);
      fresh.append(detail);
    }
    const hadFocus = card.contains(document.activeElement);
    card.replaceWith(fresh);
    card = fresh;
    replaced = true;
    document.title = title;
    if (hadFocus) {
      const heading = fresh.querySelector("h1");
      heading.tabIndex = -1;
      heading.focus();
    }
  }

  // ---------- The locked account's deadline ----------
  // As taska-ui src/lib/accountLock.ts: en-US, 24-hour; the date only when it
  // is not today; the full moment with its zone in `title`.

  const formatTime = (date) => new Intl.DateTimeFormat("en-US", { hour: "2-digit", minute: "2-digit", hourCycle: "h23" }).format(date);
  const formatDateTime = (date) =>
    new Intl.DateTimeFormat("en-US", { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit", hourCycle: "h23" }).format(date);
  const formatMoment = (date) =>
    new Intl.DateTimeFormat("en-US", {
      month: "short",
      day: "numeric",
      hour: "2-digit",
      minute: "2-digit",
      hourCycle: "h23",
      timeZoneName: "short",
    }).format(date);

  /** Rewrites every `time[data-local]` from its `datetime` into the reader's
      own clock. Its text before this runs is the UTC "HH:MM UTC". */
  function localizeTimes(root) {
    const now = new Date();
    for (const time of root.querySelectorAll("time[data-local]")) {
      const instant = Date.parse(time.dateTime);
      if (!Number.isFinite(instant)) continue;
      const date = new Date(instant);
      const today = date.getFullYear() === now.getFullYear() && date.getMonth() === now.getMonth() && date.getDate() === now.getDate();
      time.textContent = today ? formatTime(date) : formatDateTime(date);
      time.title = formatMoment(date);
    }
  }

  /** The deadline, only when it is a whole ISO instant still ahead. */
  function lockDeadline(raw) {
    if (!raw || !ISO_INSTANT.test(raw)) return null;
    const instant = Date.parse(raw);
    return Number.isFinite(instant) && instant > Date.now() ? raw : null;
  }

  function lockLines(until) {
    const lines = document.createElement("span");
    lines.className = "auth-lock";
    const first = document.createElement("span");
    if (until) {
      const time = document.createElement("time");
      time.dateTime = until;
      time.dataset.local = "";
      time.textContent = `${until.slice(11, 16)} UTC`;
      first.append("Account is locked until ", time);
    } else {
      first.textContent = "Account is locked after too many failed sign-ins.";
    }
    const second = document.createElement("span");
    second.textContent = "Try again later.";
    lines.append(first, second);
    return lines;
  }

  // ---------- A refusal from the redirect ----------

  function restoreEmail() {
    try {
      const kept = window.sessionStorage.getItem(EMAIL_KEY);
      window.sessionStorage.removeItem(EMAIL_KEY);
      if (kept && !email.value) email.value = kept;
    } catch {
      // Storage refused (private mode, policy): the field stays empty.
    }
  }

  function forgetEmail() {
    try {
      window.sessionStorage.removeItem(EMAIL_KEY);
    } catch {
      // Storage refused: nothing was kept, or nothing can be removed.
    }
  }

  /** Draws the refusal and returns the field it belongs to; focusing it is
      left to the start block, which waits for the context. */
  function showRefusal(code, until) {
    let focus = password;
    if (code === "INVALID_CREDENTIALS") {
      refusal.textContent = "Wrong email or password.";
    } else if (code === "ACCOUNT_LOCKED") {
      refusal.append(lockLines(lockDeadline(until)));
      localizeTimes(refusal);
      focus = email;
    } else if (code === "SIGNUP_REQUIRED") {
      refusal.append("Create an account in ");
      const taska = document.createElement("a");
      taska.className = "auth-link";
      taska.href = "https://taska.ozero.dev";
      taska.textContent = "Taska";
      refusal.append(taska, ", then return to the app and start connecting again.");
      focus = email;
    } else if (code === "PROVIDER_CANCELLED") {
      refusal.textContent = "Provider sign-in was cancelled. Choose a provider or use your email and password.";
      focus = email;
    } else if (code === "STATE_INVALID") {
      refusal.textContent = "This sign-in attempt expired or belongs to another browser. Choose a provider to start again.";
      focus = email;
    } else if (code === "EMAIL_NOT_VERIFIED") {
      refusal.textContent = "Verify your email address with the provider, then try again.";
      focus = email;
    } else if (code === "ACCOUNT_BLOCKED") {
      refusal.textContent = "This Taska account is blocked. Contact your administrator.";
      focus = email;
    } else if (code === "RATE_LIMITED") {
      refusal.textContent = "Too many sign-in attempts. Try again later.";
      focus = email;
    } else if (code === "PROVIDER_UNAVAILABLE") {
      refusal.textContent = "This provider is unavailable. Try again later or use your email and password.";
      focus = email;
    } else {
      // SERVICE_UNAVAILABLE and any code this page does not know. No request
      // id arrives on a redirect, so there is no detail line.
      refusal.textContent = "Sign-in could not be finished. Try again.";
    }
    refusalBox.hidden = false;
    restoreEmail();
    document.title = "Sign-in failed · Taska";
    focus.setAttribute("aria-describedby", "signin-refusal");
    return focus;
  }

  // ---------- CSRF and the pending request ----------

  function requestIdOf(response) {
    const id = response.headers.get("X-Request-Id");
    return id && /^[A-Za-z0-9._:-]{1,128}$/.test(id) ? id : null;
  }

  async function readContext() {
    ready = false;
    providerSection.hidden = true;
    providerLinks.replaceChildren();
    submit.setAttribute("aria-disabled", "true");
    let response;
    try {
      // The timeout covers the body too: a read that stalls ends as T3.
      response = await fetch("/login/context", {
        credentials: "same-origin",
        headers: { Accept: "application/json" },
        cache: "no-store",
        signal: AbortSignal.timeout(TIMEOUT_MS),
      });
    } catch {
      showTerminal("unavailable");
      return;
    }
    if (!response.ok) {
      showTerminal("unavailable", requestIdOf(response));
      return;
    }
    let context = null;
    try {
      context = await response.json();
    } catch {
      context = null;
    }
    if (!context || typeof context !== "object" || typeof context.pending_request !== "boolean") {
      showTerminal("unavailable", requestIdOf(response));
      return;
    }
    if (!context.pending_request) {
      showTerminal("expired");
      return;
    }
    const { csrf_parameter: name, csrf_token: token } = context;
    if (typeof name !== "string" || !/^[A-Za-z0-9_.-]{1,64}$/.test(name) || typeof token !== "string" || token === "") {
      showTerminal("unavailable", requestIdOf(response));
      return;
    }
    csrf.name = name;
    csrf.value = token;
    const names = { GOOGLE: "Google", GITHUB: "GitHub", APPLE: "Apple" };
    for (const provider of new Set(Array.isArray(context.providers) ? context.providers : [])) {
      if (!Object.hasOwn(names, provider)) continue;
      const link = document.createElement("a");
      link.className = "secondary-button";
      link.href = `/login/provider/${provider.toLowerCase()}`;
      link.textContent = `Continue with ${names[provider]}`;
      providerLinks.append(link);
    }
    providerSection.hidden = providerLinks.childElementCount === 0;
    ready = true;
    if (!pending) submit.removeAttribute("aria-disabled");
  }

  // ---------- Show password ----------

  toggle.hidden = false;
  toggle.addEventListener("click", () => {
    const shown = toggle.getAttribute("aria-pressed") !== "true";
    password.type = shown ? "text" : "password";
    toggle.setAttribute("aria-pressed", String(shown));
  });

  // ---------- Submit guard ----------

  form.addEventListener("submit", (event) => {
    if (!ready || pending) {
      event.preventDefault();
      return;
    }
    pending = true;
    try {
      window.sessionStorage.setItem(EMAIL_KEY, email.value);
    } catch {
      // Storage refused: a refusal will come back with the field empty.
    }
    submit.setAttribute("aria-disabled", "true");
    submit.textContent = "Signing in";
  });

  // Back to this page after it was sent: the label returns, and the token is
  // read again — signing in rotates it, and the request may be gone.
  window.addEventListener("pageshow", (event) => {
    if (!event.persisted || replaced) return;
    pending = false;
    submit.textContent = "Sign in";
    readContext();
  });

  // ---------- Start ----------

  const params = new URLSearchParams(window.location.search);
  // An email kept by an earlier submit that came back without a refusal (the
  // sign-in went through, or the request ended) has nothing left to restore.
  if (!params.has("error")) forgetEmail();
  if (params.has("logout")) {
    showTerminal("signed-out");
    return;
  }
  if (params.has("done") || params.get("error") === "SESSION_EXPIRED") {
    showTerminal("expired");
    return;
  }
  // Email on a clean load; the refusal picks its own field. Neither is focused
  // until the context says there is a request to sign in for — and then only
  // if nothing else holds focus. Placed here rather than with `autofocus`,
  // which WebKit applies after this script has run.
  let initialFocus = email;
  if (params.has("error")) initialFocus = showRefusal(params.get("error"), params.get("until"));
  readContext().then(() => {
    if (ready && !replaced && (document.activeElement === document.body || document.activeElement === null)) initialFocus.focus();
  });
})();
