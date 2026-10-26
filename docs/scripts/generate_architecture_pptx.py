"""Build docs/Analytics_Advisor_Query_Builder_Architecture.pptx."""
from __future__ import annotations

from datetime import date
from pathlib import Path

from pptx import Presentation
from pptx.dml.color import RGBColor
from pptx.enum.shapes import MSO_SHAPE
from pptx.enum.text import MSO_ANCHOR, PP_ALIGN
from pptx.oxml.ns import qn
from pptx.util import Emu, Inches, Pt

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "tech_doc_assets"
OUT = ROOT / "Analytics_Advisor_Query_Builder_Architecture.pptx"

NAVY = RGBColor(0x1E, 0x27, 0x61)
ICE = RGBColor(0xCA, 0xDC, 0xFC)
GOLD = RGBColor(0xF4, 0xA2, 0x61)
WHITE = RGBColor(0xFF, 0xFF, 0xFF)
MIST = RGBColor(0xE8, 0xEE, 0xF7)
INK = RGBColor(0x1A, 0x1A, 0x1A)
MUTED = RGBColor(0x44, 0x44, 0x44)
CREAM = RGBColor(0xFF, 0xF4, 0xE5)
GREEN = RGBColor(0xE8, 0xF5, 0xE9)

SLIDE_W = Inches(13.333)
SLIDE_H = Inches(7.5)


def set_run(run, text, size, bold=False, color=INK, font="Calibri"):
    run.text = text
    run.font.size = Pt(size)
    run.font.bold = bold
    run.font.color.rgb = color
    run.font.name = font


def add_text(shape, lines, size=16, bold=False, color=INK, align=PP_ALIGN.LEFT):
    tf = shape.text_frame
    tf.word_wrap = True
    tf.auto_size = None
    first = True
    for line in lines:
        p = tf.paragraphs[0] if first else tf.add_paragraph()
        first = False
        p.alignment = align
        p.space_after = Pt(4)
        run = p.add_run()
        if isinstance(line, tuple):
            set_run(run, line[0], line[1] if len(line) > 1 else size, line[2] if len(line) > 2 else bold, line[3] if len(line) > 3 else color)
        else:
            set_run(run, line, size, bold, color)


def rect(slide, x, y, w, h, fill, line=None):
    shape = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, x, y, w, h)
    shape.fill.solid()
    shape.fill.fore_color.rgb = fill
    shape.line.fill.background()
    if line:
        shape.line.fill.solid()
        shape.line.color.rgb = line
        shape.line.width = Pt(1.25)
    # Drop the default empty paragraph margin so cards stay tight.
    shape.text_frame.margin_left = Inches(0.12)
    shape.text_frame.margin_right = Inches(0.1)
    shape.text_frame.margin_top = Inches(0.08)
    shape.text_frame.margin_bottom = Inches(0.06)
    return shape


def rrect(slide, x, y, w, h, fill, line=NAVY):
    shape = slide.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, x, y, w, h)
    shape.fill.solid()
    shape.fill.fore_color.rgb = fill
    shape.line.color.rgb = line
    shape.line.width = Pt(1.25)
    shape.adjustments[0] = 0.08
    shape.text_frame.word_wrap = True
    shape.text_frame.auto_size = None
    shape.text_frame.margin_left = Inches(0.12)
    shape.text_frame.margin_right = Inches(0.1)
    shape.text_frame.margin_top = Inches(0.08)
    shape.text_frame.margin_bottom = Inches(0.06)
    try:
        shape.text_frame.paragraphs[0].alignment = PP_ALIGN.LEFT
    except Exception:
        pass
    sp_pr = shape._element
    # Vertical anchor via bodyPr
    body = shape.text_frame._txBody.find(qn("a:bodyPr"))
    if body is not None:
        body.set("anchor", "ctr")
    return shape


def header(slide, title, subtitle=None):
    band = rect(slide, 0, 0, SLIDE_W, Inches(1.05), NAVY)
    band.text_frame.margin_left = Inches(0.45)
    add_text(band, [title], size=28, bold=True, color=WHITE)
    if subtitle:
        bar = rect(slide, 0, Inches(1.05), SLIDE_W, Inches(0.38), MIST)
        bar.text_frame.margin_left = Inches(0.45)
        add_text(bar, [subtitle], size=13, color=NAVY)


def footer(slide, page, total):
    box = slide.shapes.add_textbox(Inches(0.45), Inches(7.15), Inches(10.5), Inches(0.28))
    add_text(box, ["Analytics Advisor  ·  Architecture"], size=11, color=MUTED)
    num = slide.shapes.add_textbox(Inches(11.6), Inches(7.15), Inches(1.3), Inches(0.28))
    add_text(num, [f"{page}  /  {total}"], size=11, color=MUTED, align=PP_ALIGN.RIGHT)


def card(slide, x, y, w, h, title, body, fill=WHITE):
    shape = rrect(slide, x, y, w, h, fill)
    tf = shape.text_frame
    tf.clear()
    p = tf.paragraphs[0]
    p.alignment = PP_ALIGN.LEFT
    run = p.add_run()
    set_run(run, title, 15, True, NAVY)
    for line in body:
        bp = tf.add_paragraph()
        bp.level = 0
        bp.space_before = Pt(3)
        br = bp.add_run()
        set_run(br, line, 13, False, INK)
    return shape


def build() -> None:
    pres = Presentation()
    pres.slide_width = SLIDE_W
    pres.slide_height = SLIDE_H
    blank = pres.slide_layouts[6]
    total = 10

    # 1 Title
    s = pres.slides.add_slide(blank)
    rect(s, 0, 0, SLIDE_W, SLIDE_H, NAVY)
    accent = rect(s, 0, Inches(5.55), SLIDE_W, Inches(0.12), GOLD)
    accent.line.fill.background()
    title = s.shapes.add_textbox(Inches(0.7), Inches(1.7), Inches(11.5), Inches(1.4))
    add_text(title, ["Analytics Advisor"], size=44, bold=True, color=WHITE)
    sub = s.shapes.add_textbox(Inches(0.7), Inches(3.15), Inches(11), Inches(1.6))
    add_text(
        sub,
        [
            "Technical architecture",
            "Two data planes, query routing, and who can reach each part",
            f"{date.today():%d %B %Y}",
        ],
        size=20,
        color=ICE,
    )

    # 2 Purpose
    s = pres.slides.add_slide(blank)
    header(s, "What the architecture is for", "An officer builds a scheme query without writing SQL")
    footer(s, 2, total)
    cards = [
        ("Compose", ["Pick tables, join keys,", "rules, and thresholds", "in Query Builder."]),
        ("Compile", ["The backend turns that", "choice into parameterised", "SQL. Values stay bound."]),
        ("Route", ["One plain external query", "goes direct. Lakehouse,", "fuzzy, and joins use Presto."]),
        ("Protect", ["Scope hides other", "geographies. Audit records", "who ran the query."]),
    ]
    for i, (t, body) in enumerate(cards):
        card(s, Inches(0.4 + i * 3.2), Inches(1.8), Inches(3.0), Inches(2.5), t, body, MIST if i % 2 == 0 else WHITE)
    note = s.shapes.add_textbox(Inches(0.5), Inches(4.7), Inches(12.2), Inches(2.0))
    add_text(
        note,
        [
            "Example. A Jaipur officer fuzzy-matches a pension name to the lakehouse beneficiary table.",
            "The product returns only Jaipur rows and writes an audit event for the run.",
            "Administrators attach databases. Officers never edit those connections.",
        ],
        size=16,
        color=INK,
    )

    # 3 Stack
    s = pres.slides.add_slide(blank)
    header(s, "Logical stack", "Browser, frontend, and backend stay separate")
    footer(s, 3, total)
    layers = [
        (MIST, "Browser", "Session token stays in the browser. English and Hindi. Officer or admin screens."),
        (WHITE, "Frontend  ·  Next.js 16 / React 19  ·  port 3000", "Overview, Query Builder, Dashboard, and the admin workspace at /admin456."),
        (ICE, "Backend  ·  Java 17 / Spring Boot 3.3  ·  port 8080", "Compiles queries, checks the token, applies scope, chooses the database, streams results."),
    ]
    for i, (fill, title, body) in enumerate(layers):
        card(s, Inches(1.4), Inches(1.7 + i * 1.55), Inches(10.5), Inches(1.35), title, [body], fill)

    # 4 Two planes
    s = pres.slides.add_slide(blank)
    header(s, "Two data planes", "The application store and the query engine are different databases")
    footer(s, 4, total)
    s.shapes.add_picture(str(ASSETS / "overview_two_planes.png"), Inches(0.35), Inches(1.55), Inches(7.3), Inches(5.2))
    card(
        s,
        Inches(7.8),
        Inches(1.7),
        Inches(5.1),
        Inches(2.3),
        "Operational",
        ["PostgreSQL on port 5433.", "Users, roles, scopes, audit,", "saved queries, registrations.", "JPA and Liquibase."],
        CREAM,
    )
    card(
        s,
        Inches(7.8),
        Inches(4.2),
        Inches(5.1),
        Inches(2.4),
        "Analytical",
        ["PrestoDB 0.297 and Iceberg.", "Lakehouse SQL.", "Local stack also uses", "Hive metastore and MinIO."],
        CREAM,
    )

    # 5 Connections vs data sources
    s = pres.slides.add_slide(blank)
    header(s, "Connections and Data Sources", "Two fixed engines, plus a list of department databases")
    footer(s, 5, total)
    card(
        s,
        Inches(0.4),
        Inches(1.7),
        Inches(6.2),
        Inches(4.9),
        "Connections  ·  always two cards",
        [
            "Operational holds the application itself.",
            "A save is tested, then applied after restart.",
            "",
            "Analytical is the Presto URL.",
            "A save is tested and applied immediately.",
            "jdbc:presto://host:8080 is enough.",
            "Each table is catalog.schema.table.",
        ],
        MIST,
    )
    card(
        s,
        Inches(6.85),
        Inches(1.7),
        Inches(6.05),
        Inches(4.9),
        "Data Sources  ·  Super Admin list",
        [
            "Add PostgreSQL or IBM DB2.",
            "Test, browse, and register tables.",
            "",
            "One plain query goes direct to that database.",
            "A PostgreSQL source can take a Presto alias",
            "so it can join the lakehouse.",
            "DB2 stays on that one database.",
        ],
        WHITE,
    )

    # 6 Query route
    s = pres.slides.add_slide(blank)
    header(s, "How a query chooses its database", "AnalysisExecutionRouter looks at the tables and the SQL")
    footer(s, 6, total)
    s.shapes.add_picture(str(ASSETS / "overview_query_route.png"), Inches(0.3), Inches(1.55), Inches(7.5), Inches(5.15))
    card(
        s,
        Inches(7.9),
        Inches(1.7),
        Inches(5.0),
        Inches(2.35),
        "Direct JDBC",
        ["One registered external table.", "Plain SQL, no fuzzy match.", "Example: district filter on", "Pension DB alone."],
        GREEN,
    )
    card(
        s,
        Inches(7.9),
        Inches(4.25),
        Inches(5.0),
        Inches(2.4),
        "Presto",
        ["Lakehouse, fuzzy names, or", "more than one source.", "Each external source needs", "a federation alias."],
        CREAM,
    )

    # 7 Security path
    s = pres.slides.add_slide(blank)
    header(s, "Sign-in and the request path", "Local mode uses BCrypt and an eight-hour token")
    footer(s, 7, total)
    steps = [
        ("1", "Sign in", "BCrypt check. Five failures lock the account for 15 minutes."),
        ("2", "Token", "HS256 JWT carries the user, roles, session version, and password-change flag."),
        ("3", "Scope", "The officer’s geography is added to the SQL. No binding means no rows."),
        ("4", "Stream", "Rows stream to the screen. A disconnect cancels the running statement."),
        ("5", "Audit", "The event stores the action, outcome, query shape, and duration."),
    ]
    for i, (n, title, body) in enumerate(steps):
        y = Inches(1.6 + i * 1.02)
        bubble = rrect(s, Inches(0.45), y, Inches(0.7), Inches(0.7), NAVY, NAVY)
        add_text(bubble, [n], size=18, bold=True, color=WHITE, align=PP_ALIGN.CENTER)
        card(s, Inches(1.4), y, Inches(11.4), Inches(0.9), title, [body], WHITE if i % 2 else MIST)

    # 8 Roles
    s = pres.slides.add_slide(blank)
    header(s, "Who can reach which surface", "The admin page requires the administrator authority")
    footer(s, 8, total)
    s.shapes.add_picture(str(ASSETS / "overview_role_menus.png"), Inches(0.25), Inches(1.5), Inches(7.6), Inches(5.2))
    card(
        s,
        Inches(8.0),
        Inches(1.65),
        Inches(4.9),
        Inches(5.0),
        "Role summary",
        [
            "Officer: Overview, Query Builder, Dashboard.",
            "",
            "Admin: those screens plus users, scopes,",
            "connections, registry, columns, backup,",
            "and guardrails.",
            "",
            "Super Admin: also Data Sources, OTP,",
            "and the Audit Log.",
            "",
            "Audit Reader alone: no Admin menu.",
        ],
        MIST,
    )

    # 9 Limits
    s = pres.slides.add_slide(blank)
    header(s, "Architecture limits", "What fails when a plane or a connector is missing")
    footer(s, 9, total)
    limits = [
        ("Presto down", "Lakehouse, fuzzy, and cross-source queries fail. The Analytical card shows the SELECT 1 error."),
        ("No DB2 connector", "A DB2 data source answers one database only. It cannot join the lakehouse."),
        ("Dormant types", "Oracle, MySQL, MariaDB, and SQL Server are defined and refused on create."),
        ("Audit Reader", "Audit-read authority does not open the admin page by itself."),
    ]
    for i, (title, body) in enumerate(limits):
        col = i % 2
        row = i // 2
        card(
            s,
            Inches(0.4 + col * 6.45),
            Inches(1.7 + row * 2.5),
            Inches(6.2),
            Inches(2.25),
            title,
            [body],
            CREAM if i % 2 == 0 else MIST,
        )

    # 10 Roadmap
    s = pres.slides.add_slide(blank)
    header(s, "Plan for the architecture", "Recommended order. This deck does not change the product.")
    footer(s, 10, total)
    phases = [
        ("Phase 1", "Bring up Presto with MinIO and the Hive metastore. Say clearly when the analytical engine is down.", ICE),
        ("Phase 2", "Enable Oracle on Data Sources, pool external JDBC, and let an Audit Reader open the Audit Log.", MIST),
        ("Phase 3", "Finish RajSewadwar sign-in, explain lockout, and stop repeating full queries for fan-out and comparisons.", CREAM),
    ]
    for i, (title, body, fill) in enumerate(phases):
        card(s, Inches(0.45), Inches(1.65 + i * 1.7), Inches(12.4), Inches(1.5), title, [body], fill)

    pres.save(OUT)
    print(f"wrote {OUT} ({OUT.stat().st_size} bytes)")


if __name__ == "__main__":
    build()
