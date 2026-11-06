import { STALE_RELAY_MS } from "./config";
import type { SessionRow, Status, UnlockConfig } from "./types";

const MIN = 60_000;

/**
 * Pure unlock rule. No I/O, takes `now` so tests can control the clock.
 *
 * claude_busy: unlocked while any session is `working` with an unexpired lease.
 * my_turn:     the same, AND a human prompt was submitted within myTurnMinutes.
 *
 * Grace: a session that reported `idle` (Stop) keeps the phone unlocked for graceSeconds,
 * so Stop followed quickly by the next turn doesn't flap. A session whose lease simply
 * ran out (it died, no Stop) gets no grace: it locks exactly at the lease.
 */
export function computeStatus(
  sessions: readonly SessionRow[],
  now: number,
  config: UnlockConfig,
  lastEventAt: number | null,
): Status {
  const graceMs = Math.max(0, config.graceSeconds) * 1000;

  const active = sessions.filter((s) => s.state === "working" && s.leaseUntil > now);
  const graceful = sessions.filter(
    (s) => s.state === "idle" && (s.lastEvent === "idle" || s.lastEvent === "end") && now - s.lastSeen < graceMs,
  );

  const relayStale = lastEventAt === null || now - lastEventAt > STALE_RELAY_MS;
  const base = { lastEventAt, relayStale, serverTime: now };

  const busy = active.length > 0 || graceful.length > 0;
  let busyLeaseUntil: number | null = null;
  if (active.length > 0) busyLeaseUntil = Math.max(...active.map((s) => s.leaseUntil));
  else if (graceful.length > 0) busyLeaseUntil = Math.max(...graceful.map((s) => s.lastSeen + graceMs));

  const lockedSince = (): number | null => {
    const seen = sessions.map((s) => s.lastSeen);
    return seen.length ? Math.max(...seen) : null;
  };

  if (!busy) {
    return {
      ...base,
      working: false,
      activeSessions: 0,
      unlockReason: relayStale ? "locked:no_events_24h" : "locked:idle",
      since: lockedSince(),
      leaseUntil: null,
    };
  }

  const workingSince = active.length
    ? Math.min(...active.map((s) => s.workingSince ?? s.lastSeen))
    : Math.max(...graceful.map((s) => s.lastSeen));

  if (config.mode === "claude_busy") {
    return {
      ...base,
      working: true,
      activeSessions: active.length,
      unlockReason: `claude_busy:${active.length} sessions`,
      since: workingSince,
      leaseUntil: busyLeaseUntil,
    };
  }

  // my_turn
  const lastPrompt = sessions.reduce<number | null>(
    (max, s) => (s.lastUserPromptAt !== null && (max === null || s.lastUserPromptAt > max) ? s.lastUserPromptAt : max),
    null,
  );
  const windowEnd = lastPrompt === null ? null : lastPrompt + config.myTurnMinutes * MIN;
  if (windowEnd === null || windowEnd <= now) {
    return {
      ...base,
      working: false,
      activeSessions: active.length,
      unlockReason: "locked:prompt_window_expired",
      since: windowEnd,
      leaseUntil: null,
    };
  }
  const minutesLeft = Math.max(1, Math.ceil((windowEnd - now) / MIN));
  return {
    ...base,
    working: true,
    activeSessions: active.length,
    unlockReason: `my_turn:${minutesLeft}m left`,
    since: lastPrompt,
    leaseUntil: Math.min(busyLeaseUntil ?? windowEnd, windowEnd),
  };
}
