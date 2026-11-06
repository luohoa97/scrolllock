import { createHash, timingSafeEqual } from "node:crypto";

const digest = (s: string) => createHash("sha256").update(s).digest();

/** Constant-time comparison that doesn't leak length. */
export function safeEqual(a: string, b: string): boolean {
  return timingSafeEqual(digest(a), digest(b));
}

export function bearer(req: Request): string | null {
  const h = req.headers.get("authorization");
  if (!h) return null;
  const m = /^Bearer\s+(.+)$/i.exec(h.trim());
  return m?.[1] ?? null;
}

/** True if the request carries a bearer token equal to `expected`. An empty expected token never matches. */
export function hasToken(req: Request, expected: string | undefined): boolean {
  const got = bearer(req);
  if (!got || !expected) return false;
  return safeEqual(got, expected);
}
