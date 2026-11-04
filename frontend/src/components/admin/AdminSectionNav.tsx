"use client";

import Link from "next/link";
import type { ReactNode } from "react";
import {
  ADMIN_SECTION_GROUPS,
  adminSectionHref,
  type AdminSectionDefinition,
  type AdminSectionId,
} from "@/app/admin456/adminSections";

function AdminSectionIcon({ id }: Readonly<{ id: AdminSectionId }>) {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      {ICON_PATHS[id]}
    </svg>
  );
}

const ICON_PATHS: Record<AdminSectionId, ReactNode> = {
  users: (
    <>
      <circle cx="12" cy="8" r="3" />
      <path d="M5.5 19.5c1.1-3.2 3.4-4.8 6.5-4.8s5.4 1.6 6.5 4.8" />
    </>
  ),
  scopes: (
    <>
      <circle cx="12" cy="5" r="2" />
      <circle cx="6" cy="18" r="2" />
      <circle cx="18" cy="18" r="2" />
      <path d="M12 7v4M12 11H6.5V16M12 11h5.5V16" />
    </>
  ),
  connections: (
    <>
      <path d="M9 7v3M15 7v3" />
      <path d="M8 10h8v3a4 4 0 01-8 0v-3z" />
      <path d="M12 17v3" />
    </>
  ),
  "data-sources": (
    <>
      <ellipse cx="12" cy="6" rx="7" ry="3" />
      <path d="M5 6v6c0 1.7 3.1 3 7 3s7-1.3 7-3V6M5 12v6c0 1.7 3.1 3 7 3s7-1.3 7-3v-6" />
    </>
  ),
  registry: (
    <>
      <rect x="4" y="5" width="16" height="14" rx="1.5" />
      <path d="M4 10h16M4 15h16M10 5v14" />
    </>
  ),
  columns: (
    <>
      <rect x="4" y="5" width="4" height="14" rx="0.5" />
      <rect x="10" y="5" width="4" height="14" rx="0.5" />
      <rect x="16" y="5" width="4" height="14" rx="0.5" />
    </>
  ),
  audit: (
    <>
      <path d="M8 3.5h7l4 4V20.5H8z" />
      <path d="M15 3.5V8h4.5M11 12.5h5M11 16.5h5" />
    </>
  ),
  lineage: (
    <>
      <circle cx="6" cy="6" r="2.5" />
      <circle cx="18" cy="6" r="2.5" />
      <circle cx="12" cy="18" r="2.5" />
      <path d="M8 7.5 L10.5 15.5M16 7.5 L13.5 15.5M8.5 6h7" />
    </>
  ),
  otp: (
    <>
      <circle cx="8" cy="14" r="3" />
      <path d="M11 14h9M17 14v2.5M20 14v2" />
    </>
  ),
  backup: (
    <>
      <path d="M4 8h16v11H4z" />
      <path d="M4 8l2-3h12l2 3" />
      <path d="M12 11v5M9.5 14.5L12 17l2.5-2.5" />
    </>
  ),
  guardrails: <path d="M12 3l8 3v6c0 4.8-3.2 7.4-8 9-4.8-1.6-8-4.2-8-9V6l8-3z" />,
};

export function AdminSectionNav({
  sections,
  activeId,
}: Readonly<{
  sections: readonly AdminSectionDefinition[];
  activeId: AdminSectionId;
}>) {
  return (
    <nav aria-label="Admin sections" className="admin-section-nav">
      {ADMIN_SECTION_GROUPS.map((group) => {
        const items = sections.filter((section) => section.group === group.id);
        if (items.length === 0) return null;
        return (
          <div key={group.id} className="admin-section-nav-group">
            <p className="admin-section-nav-label">{group.label}</p>
            <ul className="admin-section-nav-list">
              {items.map((section) => {
                const active = section.id === activeId;
                return (
                  <li key={section.id}>
                    <Link
                      href={adminSectionHref(section.id)}
                      scroll={false}
                      aria-current={active ? "page" : undefined}
                      className={active ? "admin-section-nav-link active" : "admin-section-nav-link"}
                    >
                      <AdminSectionIcon id={section.id} />
                      <span>{section.label}</span>
                    </Link>
                  </li>
                );
              })}
            </ul>
          </div>
        );
      })}
    </nav>
  );
}
