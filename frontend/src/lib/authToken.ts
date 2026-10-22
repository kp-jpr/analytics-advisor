import { readApiErrorMessage } from "@/lib/apiError";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080";
const AUTH_MODE = process.env.NEXT_PUBLIC_AUTH_MODE ?? "mock";

/** Mock-login scope: officer screens vs admin456 (admin token also carries STATE_OFFICER). */
export type AuthScope = "officer" | "admin";

const STORAGE_KEY = "srse.auth.token";

const tokenCache = new Map<AuthScope, Promise<string>>();

export class SrseAdminAccessDeniedError extends Error {
  constructor() {
    super("This account is not an SRSE administrator.");
    this.name = "SrseAdminAccessDeniedError";
  }
}

export class SrseAuthRequiredError extends Error {
  constructor() {
    super("Authentication required.");
    this.name = "SrseAuthRequiredError";
  }
}

export type AuthSession = {
  username: string;
  roles: string[];
  authorities: string[];
  admin: boolean;
  superAdmin: boolean;
  auditReader: boolean;
  mustChangePassword: boolean;
  active: boolean;
};

function readStoredToken(): string | null {
  if (typeof window === "undefined") {
    return null;
  }
  return sessionStorage.getItem(STORAGE_KEY);
}

/** Session bearer from local login (null in mock mode until stored). */
export function readStoredAuthToken(): string | null {
  return readStoredToken();
}

export function storeAuthToken(token: string): void {
  sessionStorage.setItem(STORAGE_KEY, token);
  tokenCache.clear();
}

export function clearAuthToken(): void {
  sessionStorage.removeItem(STORAGE_KEY);
  tokenCache.clear();
}

export function isLocalAuthMode(): boolean {
  return AUTH_MODE === "local";
}

export function getAuthToken(scope: AuthScope = "officer"): Promise<string> {
  if (AUTH_MODE === "local") {
    const stored = readStoredToken();
    if (!stored) {
      return Promise.reject(new SrseAuthRequiredError());
    }
    return Promise.resolve(stored);
  }

  let cached = tokenCache.get(scope);
  if (!cached) {
    const loginUrl =
      scope === "admin"
        ? `${API_BASE}/api/auth/mock-login?role=admin`
        : `${API_BASE}/api/auth/mock-login`;
    cached = fetch(loginUrl, { method: "POST" })
      .then(async (res) => {
        if (!res.ok) {
          throw new Error(`Mock login failed ${res.status}: ${await readApiErrorMessage(res)}`);
        }
        const body = (await res.json()) as { token: string };
        return body.token;
      })
      .catch((err) => {
        tokenCache.delete(scope);
        throw err;
      });
    tokenCache.set(scope, cached);
  }
  return cached;
}

export async function switchActiveRole(role: string): Promise<void> {
  const token = readStoredToken();
  if (!token) {
    throw new SrseAuthRequiredError();
  }
  const res = await fetch(`${API_BASE}/api/auth/switch-role`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify({ role }),
  });
  if (!res.ok) {
    throw new Error(`Switch role failed ${res.status}: ${await readApiErrorMessage(res)}`);
  }
  const body = (await res.json()) as { token?: string | null };
  if (!body.token) {
    throw new Error("Switch role did not return a token");
  }
  storeAuthToken(body.token);
  tokenCache.clear();
}

export async function fetchAuthSession(): Promise<AuthSession | null> {
  let token: string;
  try {
    token = await getAuthToken("officer");
  } catch {
    return null;
  }
  const res = await fetch(`${API_BASE}/api/auth/session`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (res.status === 401) {
    clearAuthToken();
    return null;
  }
  if (!res.ok) {
    return null;
  }
  return (await res.json()) as AuthSession;
}

export async function authorizedFetch(
  scope: AuthScope,
  input: string,
  init: RequestInit = {},
): Promise<Response> {
  let token: string;
  try {
    token = await getAuthToken(scope);
  } catch (err) {
    if (err instanceof SrseAuthRequiredError && typeof window !== "undefined") {
      window.location.href = `/login?returnTo=${encodeURIComponent(window.location.pathname)}`;
    }
    throw err;
  }
  const res = await fetch(input, {
    ...init,
    headers: { ...init.headers, Authorization: `Bearer ${token}` },
  });
  if (res.status === 401) {
    clearAuthToken();
    if (typeof window !== "undefined") {
      window.location.href = `/login?returnTo=${encodeURIComponent(window.location.pathname)}`;
    }
    throw new SrseAuthRequiredError();
  }
  if (scope === "admin" && res.status === 403) {
    const session = await fetchAuthSession();
    if (session?.mustChangePassword) {
      if (typeof window !== "undefined" && !window.location.pathname.startsWith("/change-password")) {
        window.location.href = "/change-password";
      }
    } else {
      throw new SrseAdminAccessDeniedError();
    }
  }
  return res;
}

/** Officers who signed in from the admin URL go to the workspace, not the admin gate. */
export function pathAfterLogin(returnTo: string, admin: boolean): string {
  const target = returnTo.startsWith("/") ? returnTo : "/overview";
  const path = target.split("?")[0].split("#")[0];
  if (!admin && (path === "/admin456" || path.startsWith("/admin456/"))) {
    return "/overview";
  }
  return target;
}
