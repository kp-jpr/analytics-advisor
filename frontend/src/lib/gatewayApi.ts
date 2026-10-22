import { readApiErrorMessage } from "@/lib/apiError";
import { authorizedFetch } from "@/lib/authToken";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080";

export type SmtpGatewayView = {
  host: string | null;
  port: number;
  username: string | null;
  fromAddress: string | null;
  tls: boolean;
  passwordConfigured: boolean;
};

export type SmtpGatewayUpdateRequest = {
  host: string;
  port: number;
  username: string;
  password: string;
  fromAddress: string;
  tls: boolean;
};

export type SmsGatewayView = {
  endpoint: string | null;
  username: string | null;
  senderId: string | null;
  passwordConfigured: boolean;
};

export type SmsGatewayUpdateRequest = {
  endpoint: string;
  username: string;
  password: string;
  senderId: string;
};

export type GatewayTestResult = {
  success: boolean;
  message: string;
};

async function adminJson<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await authorizedFetch("admin", `${API_BASE}${path}`, {
    credentials: "include",
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...(init?.headers ?? {}),
    },
  });
  if (!res.ok) {
    throw new Error(await readApiErrorMessage(res));
  }
  return res.json() as Promise<T>;
}

export function fetchSmtpGateway(): Promise<SmtpGatewayView> {
  return adminJson("/api/admin/otp-gateway/smtp");
}

export function saveSmtpGateway(body: SmtpGatewayUpdateRequest): Promise<SmtpGatewayView> {
  return adminJson("/api/admin/otp-gateway/smtp", { method: "PUT", body: JSON.stringify(body) });
}

export function testSmtpGateway(): Promise<GatewayTestResult> {
  return adminJson("/api/admin/otp-gateway/smtp/test", { method: "POST" });
}

export function fetchSmsGateway(): Promise<SmsGatewayView> {
  return adminJson("/api/admin/otp-gateway/sms");
}

export function saveSmsGateway(body: SmsGatewayUpdateRequest): Promise<SmsGatewayView> {
  return adminJson("/api/admin/otp-gateway/sms", { method: "PUT", body: JSON.stringify(body) });
}

export function testSmsGateway(): Promise<GatewayTestResult> {
  return adminJson("/api/admin/otp-gateway/sms/test", { method: "POST" });
}
