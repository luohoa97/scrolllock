/** Server-side timing. The unlock rules in unlock.ts read these as defaults. */
export const LEASE_MS = 90_000; // a working session with no news for this long counts as dead
export const TOOL_LEASE_MS = 10 * 60_000; // a tool is running: allow a long silence
/** A hook may ask for a shorter or longer lease per event (lease_ms). Clamped to this range. */
export const MIN_LEASE_MS = 3_000;
export const MAX_LEASE_MS = 120_000;
export const DEFAULT_GRACE_SECONDS = 10; // idle only reports locked after this, so back-to-back turns don't flap
export const DEFAULT_MY_TURN_MINUTES = 15;
/** A "waiting on you" flag that nobody cleared (the thread died) stops locking after this. Locking is the safe failure, but not forever. */
export const NEEDS_YOU_MS = 3 * 60 * 60_000;
export const STALE_RELAY_MS = 24 * 60 * 60_000; // no hook events for a day: hooks probably broken
export const PUSH_EXTEND_MS = 30_000; // coalesce lease-extension pushes
export const KEEP_IDLE_ROWS_MS = 7 * 24 * 60 * 60_000;
export const META_LAST_EVENT_AT = "lastEventAt";
