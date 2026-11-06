// Which part of Instagram is this URL? Messages always pass.
(function (root) {
  /** Returns "reels" | "explore" | "dm" | "other". */
  function classify(pathname) {
    const p = (pathname || "/").toLowerCase();
    if (p === "/direct" || p.startsWith("/direct/")) return "dm";
    if (p === "/reels" || p.startsWith("/reels/") || p.startsWith("/reel/")) return "reels";
    if (p === "/explore" || p.startsWith("/explore/")) return "explore";
    return "other";
  }

  function isBlocked(pathname, cfg) {
    const c = classify(pathname);
    return (c === "reels" && cfg.blockReels !== false) || (c === "explore" && cfg.blockExplore !== false);
  }

  const api = { classify, isBlocked };
  root.ScrollLockRoute = api;
  if (typeof module !== "undefined") module.exports = api;
})(typeof globalThis !== "undefined" ? globalThis : this);
