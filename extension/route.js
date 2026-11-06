// Which part of Instagram is this URL? Block all: while locked, everything on instagram.com is
// blocked, messages included. Only the login pages stay reachable so you can sign in.
(function (root) {
  /** Returns "reels" | "explore" | "dm" | "account" | "other". */
  function classify(pathname) {
    const p = (pathname || "/").toLowerCase();
    if (p === "/direct" || p.startsWith("/direct/")) return "dm";
    if (p.startsWith("/accounts/")) return "account";
    if (p === "/reels" || p.startsWith("/reels/") || p.startsWith("/reel/")) return "reels";
    if (p === "/explore" || p.startsWith("/explore/")) return "explore";
    return "other";
  }

  function isBlocked(pathname) {
    const c = classify(pathname);
    return c !== "account";
  }

  const api = { classify, isBlocked };
  root.ScrollLockRoute = api;
  if (typeof module !== "undefined") module.exports = api;
})(typeof globalThis !== "undefined" ? globalThis : this);
