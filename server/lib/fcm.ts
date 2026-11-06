import { SignJWT, importPKCS8 } from "jose";
import type { Status } from "./types";

interface ServiceAccount {
  project_id: string;
  client_email: string;
  private_key: string;
}

export function parseServiceAccount(raw: string | undefined): ServiceAccount | null {
  if (!raw) return null;
  const text = raw.trim().startsWith("{") ? raw : Buffer.from(raw, "base64").toString("utf8");
  try {
    const j = JSON.parse(text) as Partial<ServiceAccount>;
    if (j.project_id && j.client_email && j.private_key) return j as ServiceAccount;
  } catch {
    /* fall through */
  }
  return null;
}

export type PushResult = "sent" | "unregistered" | "error";

export interface Pusher {
  send(deviceToken: string, status: Status): Promise<PushResult>;
}

/** FCM HTTP v1 sender. `fetchImpl` is injectable for tests. */
export function createFcmPusher(sa: ServiceAccount, fetchImpl: typeof fetch = fetch): Pusher {
  let cached: { token: string; exp: number } | null = null;

  async function accessToken(now = Date.now()): Promise<string> {
    if (cached && cached.exp - 60_000 > now) return cached.token;
    const key = await importPKCS8(sa.private_key, "RS256");
    const jwt = await new SignJWT({ scope: "https://www.googleapis.com/auth/firebase.messaging" })
      .setProtectedHeader({ alg: "RS256", typ: "JWT" })
      .setIssuer(sa.client_email)
      .setSubject(sa.client_email)
      .setAudience("https://oauth2.googleapis.com/token")
      .setIssuedAt()
      .setExpirationTime("55m")
      .sign(key);
    const res = await fetchImpl("https://oauth2.googleapis.com/token", {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion: jwt }),
    });
    if (!res.ok) throw new Error(`oauth ${res.status}`);
    const j = (await res.json()) as { access_token: string; expires_in: number };
    cached = { token: j.access_token, exp: now + j.expires_in * 1000 };
    return cached.token;
  }

  return {
    async send(deviceToken, s) {
      try {
        const res = await fetchImpl(`https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`, {
          method: "POST",
          headers: { authorization: `Bearer ${await accessToken()}`, "content-type": "application/json" },
          body: JSON.stringify({
            message: {
              token: deviceToken,
              android: { priority: "HIGH", ttl: "120s" },
              data: {
                working: String(s.working),
                reason: s.unlockReason,
                since: String(s.since ?? ""),
                leaseUntil: String(s.leaseUntil ?? ""),
                sessions: String(s.activeSessions),
                serverTime: String(s.serverTime),
              },
            },
          }),
        });
        if (res.ok) return "sent";
        const body = await res.text();
        if (res.status === 404 || /UNREGISTERED|NOT_FOUND/.test(body)) return "unregistered";
        return "error";
      } catch {
        return "error";
      }
    },
  };
}
