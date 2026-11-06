import { generateKeyPairSync } from "node:crypto";
import { describe, expect, it } from "vitest";
import { createFcmPusher, parseServiceAccount } from "../lib/fcm";
import type { Status } from "../lib/types";

const status: Status = {
  working: true, activeSessions: 2, unlockReason: "claude_busy:2 sessions", since: 5, leaseUntil: 99,
  lastEventAt: 5, relayStale: false, serverTime: 6,
};

function fakeSa() {
  const { privateKey } = generateKeyPairSync("rsa", { modulusLength: 2048, privateKeyEncoding: { type: "pkcs8", format: "pem" }, publicKeyEncoding: { type: "spki", format: "pem" } });
  return { project_id: "demo", client_email: "svc@demo.iam.gserviceaccount.com", private_key: privateKey };
}

describe("parseServiceAccount", () => {
  it("accepts raw JSON and base64, rejects junk", () => {
    const sa = fakeSa();
    const raw = JSON.stringify(sa);
    expect(parseServiceAccount(raw)?.project_id).toBe("demo");
    expect(parseServiceAccount(Buffer.from(raw).toString("base64"))?.project_id).toBe("demo");
    expect(parseServiceAccount("nope")).toBeNull();
    expect(parseServiceAccount(undefined)).toBeNull();
  });
});

describe("createFcmPusher", () => {
  it("sends a high-priority data message with string values and reuses the access token", async () => {
    const calls: { url: string; body: unknown }[] = [];
    const fetchImpl = (async (url: string, init?: RequestInit) => {
      calls.push({ url, body: init?.body });
      if (url.includes("oauth2")) return new Response(JSON.stringify({ access_token: "tok", expires_in: 3600 }));
      return new Response("{}", { status: 200 });
    }) as unknown as typeof fetch;
    const p = createFcmPusher(fakeSa(), fetchImpl);
    expect(await p.send("device-token", status)).toBe("sent");
    expect(await p.send("device-token", status)).toBe("sent");
    expect(calls.filter((c) => c.url.includes("oauth2"))).toHaveLength(1);
    const msg = JSON.parse(calls.find((c) => c.url.includes("messages:send"))?.body as string).message;
    expect(msg.token).toBe("device-token");
    expect(msg.android.priority).toBe("HIGH");
    expect(msg.data).toEqual({ working: "true", reason: "claude_busy:2 sessions", since: "5", leaseUntil: "99", sessions: "2", serverTime: "6" });
  });

  it("maps 404 to unregistered and network failure to error", async () => {
    const ok = (async (url: string) => (url.includes("oauth2") ? new Response(JSON.stringify({ access_token: "t", expires_in: 3600 })) : new Response("UNREGISTERED", { status: 404 }))) as unknown as typeof fetch;
    expect(await createFcmPusher(fakeSa(), ok).send("t", status)).toBe("unregistered");
    const boom = (async () => { throw new Error("net"); }) as unknown as typeof fetch;
    expect(await createFcmPusher(fakeSa(), boom).send("t", status)).toBe("error");
  });
});
