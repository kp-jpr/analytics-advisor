"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { readApiErrorMessage } from "@/lib/apiError";
import {
  clearAuthToken,
  fetchAuthSession,
  getAuthToken,
  isLocalAuthMode,
  storeAuthToken,
} from "@/lib/authToken";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080";

export default function ChangePasswordPage() {
  const router = useRouter();
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [username, setUsername] = useState("");

  useEffect(() => {
    fetchAuthSession().then((s) => setUsername(s?.username ?? ""));
  }, []);

  if (!isLocalAuthMode()) {
    return (
      <main className="srse-page">
        <p>Password change applies to local auth mode only.</p>
      </main>
    );
  }

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (newPassword !== confirm) {
      setError("New passwords do not match");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const token = await getAuthToken("officer");
      const res = await fetch(`${API_BASE}/api/auth/change-password`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${token}`,
        },
        body: JSON.stringify({ currentPassword, newPassword }),
      });
      if (res.status === 401) {
        clearAuthToken();
        router.replace("/login");
        return;
      }
      if (!res.ok) {
        setError(await readApiErrorMessage(res));
        return;
      }
      clearAuthToken();
      if (username) {
        const loginRes = await fetch(`${API_BASE}/api/auth/login`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ username, password: newPassword }),
        });
        const body = (await loginRes.json()) as { token?: string };
        if (loginRes.ok && body.token) {
          storeAuthToken(body.token);
          router.replace("/analysis");
          return;
        }
      }
      router.replace("/login?returnTo=/analysis");
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="srse-page" style={{ maxWidth: "28rem", margin: "2rem auto" }}>
      <section className="srse-card">
        <h1 className="srse-card-title">Change password</h1>
        <p className="srse-muted">You must set a new password before using the application.</p>
        <form onSubmit={onSubmit} className="srse-form-stack">
          <label>
            Current password
            <input
              className="srse-input"
              type="password"
              value={currentPassword}
              onChange={(e) => setCurrentPassword(e.target.value)}
              required
            />
          </label>
          <label>
            New password
            <input
              className="srse-input"
              type="password"
              value={newPassword}
              onChange={(e) => setNewPassword(e.target.value)}
              required
            />
          </label>
          <label>
            Confirm new password
            <input
              className="srse-input"
              type="password"
              value={confirm}
              onChange={(e) => setConfirm(e.target.value)}
              required
            />
          </label>
          {error ? <p className="srse-error-text">{error}</p> : null}
          <button type="submit" className="srse-btn srse-btn-primary" disabled={busy}>
            {busy ? "Saving…" : "Update password"}
          </button>
        </form>
      </section>
    </main>
  );
}
