// Which part of Instagram is this URL? Default deny: while locked, everything on instagram.com is
// blocked except messages and the login pages needed to reach them.
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
    return c !== "dm" && c !== "account";
  }

  const api = { classify, isBlocked };
  root.ScrollLockRoute = api;
  if (typeof module !== "undefined") module.exports = api;
})(typeof globalThis !== "undefined" ? globalThis : this);
