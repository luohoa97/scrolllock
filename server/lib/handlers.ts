import { hasToken } from "./auth";
import { DEFAULT_GRACE_SECONDS, DEFAULT_MY_TURN_MINUTES, KEEP_IDLE_ROWS_MS, LEASE_MS, MAX_LEASE_MS, META_LAST_EVENT_AT, MIN_LEASE_MS } from "./config";
import { applyEvent } from "./events";
import type { Pusher } from "./fcm";
import { pushChanges } from "./push";
import { computeStatus } from "./unlock";
import { EVENT_NAMES, type Device, type EventName, type Mode, type Store } from "./types";

export interface Deps {
  store: Store;
  pusher: Pusher | null;
  now: () => number;
  env: { WRITE_TOKEN?: string; READ_TOKEN?: string; CRON_SECRET?: string };
}

const json = (body: unknown, status = 200): Response =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json", "cache-control": "no-store" } });

const unauthorized = () => json({ error: "unauthorized" }, 401);
const bad = (error: string) => json({ error }, 400);

async function readJson(req: Request): Promise<Record<string, unknown> | null> {
  try {
    const v: unknown = await req.json();
    return v && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}

const parseMode = (v: unknown): Mode | null => (v === "claude_busy" || v === "my_turn" ? v : null);
const clampInt = (v: unknown, min: number, max: number, fallback: number): number => {
  const n = typeof v === "string" || typeof v === "number" ? Number(v) : NaN;
  return Number.isFinite(n) ? Math.min(max, Math.max(min, Math.round(n))) : fallback;
};

/** POST /api/event  {session_id, event, human?, lease_ms?}  (write token) */
export async function handleEvent(req: Request, d: Deps): Promise<Response> {
  if (!hasToken(req, d.env.WRITE_TOKEN)) return unauthorized();
  const body = await readJson(req);
  if (!body) return bad("invalid json");
  const sessionId = body.session_id;
  const event = body.event;
  if (typeof sessionId !== "string" || sessionId.length === 0 || sessionId.length > 200) return bad("session_id");
  if (typeof event !== "string" || !(EVENT_NAMES as readonly string[]).includes(event)) return bad("event");

  const now = d.now();
  const leaseMs = body.lease_ms === undefined ? undefined : clampInt(body.lease_ms, MIN_LEASE_MS, MAX_LEASE_MS, LEASE_MS);
  await applyEvent(d.store, { sessionId, event: event as EventName, human: body.human === true, leaseMs }, now);
  await pushChanges(d.store, d.pusher, now);
  return json({ ok: true });
}

/** GET /api/status?mode=&minutes=&grace=  (read token) */
export async function handleStatus(req: Request, d: Deps): Promise<Response> {
  if (!hasToken(req, d.env.READ_TOKEN)) return unauthorized();
  const url = new URL(req.url);
  const mode = parseMode(url.searchParams.get("mode")) ?? "claude_busy";
  const myTurnMinutes = clampInt(url.searchParams.get("minutes"), 1, 240, DEFAULT_MY_TURN_MINUTES);
  const graceSeconds = clampInt(url.searchParams.get("grace"), 0, 120, DEFAULT_GRACE_SECONDS);
  const now = d.now();
  const last = await d.store.getMeta(META_LAST_EVENT_AT);
  const status = computeStatus(await d.store.listSessions(), now, { mode, myTurnMinutes, graceSeconds }, last === null ? null : Number(last));
  return json(status);
}

/** POST /api/register-device  {token, mode?, minutes?, grace?}  (read token) */
export async function handleRegisterDevice(req: Request, d: Deps): Promise<Response> {
  if (!hasToken(req, d.env.READ_TOKEN)) return unauthorized();
  const body = await readJson(req);
  if (!body) return bad("invalid json");
  const token = body.token;
  if (typeof token !== "string" || token.length < 20 || token.length > 4096) return bad("token");

  const existing = (await d.store.listDevices()).find((x) => x.token === token);
  const device: Device = {
    token,
    mode: parseMode(body.mode) ?? existing?.mode ?? "claude_busy",
    myTurnMinutes: clampInt(body.minutes, 1, 240, existing?.myTurnMinutes ?? DEFAULT_MY_TURN_MINUTES),
    graceSeconds: clampInt(body.grace, 0, 120, existing?.graceSeconds ?? DEFAULT_GRACE_SECONDS),
    // A fresh config may change the answer, so force the next compare to push.
    lastPushWorking: null,
    lastPushLeaseUntil: null,
    lastPushAt: existing?.lastPushAt ?? null,
  };
  await d.store.putDevice(device);
  await pushChanges(d.store, d.pusher, d.now());
  return json({ ok: true });
}

/** GET /api/cron/sweep  (Vercel cron, Bearer CRON_SECRET) */
export async function handleSweep(req: Request, d: Deps): Promise<Response> {
  if (!hasToken(req, d.env.CRON_SECRET)) return unauthorized();
  const now = d.now();
  const pushed = await pushChanges(d.store, d.pusher, now);
  const deleted = await d.store.deleteSessionsIdleBefore(now - KEEP_IDLE_ROWS_MS);
  return json({ ok: true, pushed, deleted });
}
