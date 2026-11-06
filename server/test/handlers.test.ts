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

describe("agents and task progress", () => {
  const status = async (deps: Deps) => (await (await handleStatus(get("/api/status", "r-token"), deps)).json()) as Status;
  const ev = (deps: Deps, body: Record<string, unknown>) => handleEvent(post("/api/event", { session_id: "a", ...body }, "w-token"), deps);

  it("lists every working agent with its own progress, newest first", async () => {
    const { deps, clock } = mk();
    await ev(deps, { event: "working", human: true, agent: "claude", lease_ms: 60_000 });
    await ev(deps, { event: "progress", tasks_done: 3, tasks_total: 7, lease_ms: 60_000 });
    clock.t += 1000;
    await handleEvent(post("/api/event", { session_id: "b", event: "working", agent: "codex", lease_ms: 60_000 }, "w-token"), deps);
    const st = await status(deps);
    expect(st.agents).toEqual([
      { agent: "codex", done: null, total: null, now: null, next: null },
      { agent: "claude", done: 3, total: 7, now: null, next: null },
    ]);
    // later events keep the label and the counts
    await ev(deps, { event: "heartbeat", lease_ms: 60_000 });
    await ev(deps, { event: "tool_done", lease_ms: 60_000 });
    expect((await status(deps)).agents.find((x) => x.agent === "claude")).toMatchObject({ done: 3, total: 7 });
  });

  it("keeps task text only when the sender sent it, trimmed and stripped of control characters", async () => {
    const { deps } = mk();
    await ev(deps, { event: "working", human: true, agent: "claude" });
    await ev(deps, { event: "progress", tasks_done: 1, tasks_total: 3, tasks_now: "  write\n the\u0007 migration  ", tasks_next: "x".repeat(500) });
    const a = (await status(deps)).agents[0]!;
    expect(a.now).toBe("write the migration");
    expect(a.next).toHaveLength(120);
  });

  it("ignores a bad agent label, and never lists a locked session or lets progress unlock", async () => {
    const { deps } = mk();
    await ev(deps, { event: "progress", tasks_done: 1, tasks_total: 4, agent: "claude" });
    const st = await status(deps);
    expect(st.working).toBe(false);
    expect(st.agents).toEqual([]);
    await ev(deps, { event: "working", agent: "<script>" });
    expect((await status(deps)).agents[0]!.agent).toBe("Agent");
  });

  it("clears the task list on end and clamps nonsense", async () => {
    const { deps } = mk();
    await ev(deps, { event: "working", human: true, agent: "claude" });
    await ev(deps, { event: "progress", tasks_done: 99, tasks_total: 5 });
    expect((await status(deps)).agents[0]!).toMatchObject({ done: 5, total: 5 });
    await ev(deps, { event: "end" });
    await ev(deps, { event: "working", human: true });
    expect((await status(deps)).agents[0]!).toMatchObject({ done: null, total: null });
  });
});

describe("waiting on you", () => {
  const status = async (deps: Deps) => (await (await handleStatus(get("/api/status", "r-token"), deps)).json()) as Status;
  const ev = (deps: Deps, body: Record<string, unknown>) => handleEvent(post("/api/event", { session_id: "a", ...body }, "w-token"), deps);

  it("locks even while agents are working, and says who is waiting", async () => {
    const { deps } = mk();
    await ev(deps, { event: "working", human: true, agent: "claude", lease_ms: 60_000 });
    expect((await status(deps)).working).toBe(true);
    await handleEvent(post("/api/event", { session_id: "coord", event: "needs_you", agent: "coordinator", reason: "question", label: "Organisely" }, "w-token"), deps);
    const st = await status(deps);
    expect(st.working).toBe(false);
    expect(st.unlockReason).toBe("locked:needs_you");
    expect(st.needsYou).toBe(1);
    expect(st.waiting).toEqual([{ agent: "coordinator", reason: "question", label: "Organisely" }]);
  });

  it("clears on resolved, on the human's next prompt, on a tool that then ran, and when the turn ends", async () => {
    for (const clear of [{ event: "resolved" }, { event: "working", human: true }, { event: "tool_done" }, { event: "idle" }, { event: "end" }]) {
      const { deps } = mk();
      await ev(deps, { event: "working", human: true, lease_ms: 60_000 });
      await ev(deps, { event: "needs_you", reason: "permission" });
      expect((await status(deps)).working).toBe(false);
      await ev(deps, { ...clear, lease_ms: 60_000 });
      expect((await status(deps)).needsYou).toBe(0);
    }
  });

  it("survives beats and heartbeats, which fire before the permission is answered", async () => {
    const { deps } = mk();
    await ev(deps, { event: "working", human: true, lease_ms: 60_000 });
    await ev(deps, { event: "needs_you", reason: "permission" });
    await ev(deps, { event: "beat", lease_ms: 60_000 });
    await ev(deps, { event: "heartbeat", lease_ms: 60_000 });
    await ev(deps, { event: "progress", tasks_done: 1, tasks_total: 2 });
    expect((await status(deps)).needsYou).toBe(1);
  });

  it("stops locking after the expiry, and keeps label text to short clean strings", async () => {
    const { deps, clock } = mk();
    await ev(deps, { event: "working", human: true, lease_ms: 120_000 });
    await ev(deps, { event: "needs_you", label: "x".repeat(300) });
    expect((await status(deps)).waiting[0]!.label).toHaveLength(60);
    clock.t += 3 * 60 * 60_000 + 1;
    expect((await status(deps)).needsYou).toBe(0);
  });
});

