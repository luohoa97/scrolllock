import { describe, expect, it } from "vitest";
import type { Pusher } from "../lib/fcm";
import { handleEvent, handleRegisterDevice, handleStatus, handleSweep, type Deps } from "../lib/handlers";
import { MemoryStore } from "../lib/memory-store";
import type { Status } from "../lib/types";

const T0 = 1_800_000_000_000;

function mk(pusher: Pusher | null = null) {
  const clock = { t: T0 };
  const store = new MemoryStore();
  const deps: Deps = { store, pusher, now: () => clock.t, env: { WRITE_TOKEN: "w-token", READ_TOKEN: "r-token", CRON_SECRET: "c-secret" } };
  return { clock, store, deps };
}
const post = (path: string, body: unknown, token?: string) =>
  new Request(`https://x.test${path}`, {
    method: "POST",
    headers: { "content-type": "application/json", ...(token ? { authorization: `Bearer ${token}` } : {}) },
    body: typeof body === "string" ? body : JSON.stringify(body),
  });
const get = (path: string, token?: string) =>
  new Request(`https://x.test${path}`, { headers: token ? { authorization: `Bearer ${token}` } : {} });

describe("auth", () => {
  it("rejects missing, wrong and cross-scope tokens", async () => {
    const { deps } = mk();
    expect((await handleEvent(post("/api/event", { session_id: "a", event: "working" }), deps)).status).toBe(401);
    expect((await handleEvent(post("/api/event", { session_id: "a", event: "working" }, "nope"), deps)).status).toBe(401);
    // the read token must not write, the write token must not read
    expect((await handleEvent(post("/api/event", { session_id: "a", event: "working" }, "r-token"), deps)).status).toBe(401);
    expect((await handleStatus(get("/api/status", "w-token"), deps)).status).toBe(401);
    expect((await handleRegisterDevice(post("/api/register-device", { token: "x".repeat(30) }, "w-token"), deps)).status).toBe(401);
    expect((await handleSweep(get("/api/cron/sweep", "r-token"), deps)).status).toBe(401);
  });

  it("never accepts anything when a token env var is unset", async () => {
    const { deps } = mk();
    deps.env.WRITE_TOKEN = undefined;
    expect((await handleEvent(post("/api/event", { session_id: "a", event: "working" }, ""), deps)).status).toBe(401);
  });
});

describe("POST /api/event", () => {
  it("validates input", async () => {
    const { deps } = mk();
    expect((await handleEvent(post("/api/event", "not json", "w-token"), deps)).status).toBe(400);
    expect((await handleEvent(post("/api/event", { event: "working" }, "w-token"), deps)).status).toBe(400);
    expect((await handleEvent(post("/api/event", { session_id: "a", event: "nope" }, "w-token"), deps)).status).toBe(400);
  });
});

describe("GET /api/status", () => {
  it("fake-claude working unlocks, and silence locks after 90s", async () => {
    const { deps, clock } = mk();
    expect((await handleEvent(post("/api/event", { session_id: "fake", event: "working", human: true }, "w-token"), deps)).status).toBe(200);
    let st = (await (await handleStatus(get("/api/status?mode=claude_busy", "r-token"), deps)).json()) as Status;
    expect(st.working).toBe(true);
    expect(st.activeSessions).toBe(1);

    clock.t = T0 + 89_000;
    st = (await (await handleStatus(get("/api/status", "r-token"), deps)).json()) as Status;
    expect(st.working).toBe(true);

    clock.t = T0 + 90_000;
    st = (await (await handleStatus(get("/api/status", "r-token"), deps)).json()) as Status;
    expect(st.working).toBe(false);
  });

  it("honours my_turn query params and clamps junk", async () => {
    const { deps, clock } = mk();
    await handleEvent(post("/api/event", { session_id: "a", event: "heartbeat" }, "w-token"), deps); // no human prompt
    clock.t = T0 + 1000;
    const st = (await (await handleStatus(get("/api/status?mode=my_turn&minutes=abc&grace=-5", "r-token"), deps)).json()) as Status;
    expect(st.working).toBe(false);
    expect(st.unlockReason).toBe("locked:prompt_window_expired");
  });
});

describe("push", () => {
  it("pushes on flip and on lease extension, not on every heartbeat", async () => {
    const sent: Status[] = [];
    const pusher: Pusher = { send: async (_t, s) => (sent.push(s), "sent") };
    const { deps, clock, store } = mk(pusher);
    await handleRegisterDevice(post("/api/register-device", { token: "d".repeat(30), mode: "claude_busy", grace: 10 }, "r-token"), deps);
    expect(sent.length).toBe(1); // initial: locked
    expect(sent[0]?.working).toBe(false);

    await handleEvent(post("/api/event", { session_id: "a", event: "working", human: true }, "w-token"), deps);
    expect(sent.length).toBe(2);
    expect(sent[1]?.working).toBe(true);
    expect(sent[1]?.leaseUntil).toBe(T0 + 90_000);

    clock.t = T0 + 10_000; // lease would extend by only 10s: coalesced
    await handleEvent(post("/api/event", { session_id: "a", event: "tool_done" }, "w-token"), deps);
    expect(sent.length).toBe(2);

    clock.t = T0 + 45_000; // extends by 45s: push
    await handleEvent(post("/api/event", { session_id: "a", event: "tool_done" }, "w-token"), deps);
    expect(sent.length).toBe(3);

    // session dies silently: the cron backstop pushes the lock
    clock.t = T0 + 45_000 + 90_000;
    await handleSweep(get("/api/cron/sweep", "c-secret"), deps);
    expect(sent.length).toBe(4);
    expect(sent[3]?.working).toBe(false);
    expect((await store.listDevices())[0]?.lastPushWorking).toBe(false);
  });

  it("drops devices FCM reports as unregistered", async () => {
    const pusher: Pusher = { send: async () => "unregistered" };
    const { deps, store } = mk(pusher);
    await handleRegisterDevice(post("/api/register-device", { token: "d".repeat(30) }, "r-token"), deps);
    expect(await store.listDevices()).toHaveLength(0);
  });

  it("keeps the device and does not mark it pushed when sending errors", async () => {
    const pusher: Pusher = { send: async () => "error" };
    const { deps, store } = mk(pusher);
    await handleRegisterDevice(post("/api/register-device", { token: "d".repeat(30) }, "r-token"), deps);
    const d = (await store.listDevices())[0];
    expect(d?.lastPushWorking).toBeNull();
  });

  it("rejects absurd device tokens", async () => {
    const { deps } = mk();
    expect((await handleRegisterDevice(post("/api/register-device", { token: "short" }, "r-token"), deps)).status).toBe(400);
  });
});
