"""Explain how Analytics Advisor writes queries, enforces roles, fuzzy-matches, and which stack it uses.

Writes docs/Query_Builder_Roles_Fuzzy_and_Stack.docx.
Does not replace the architecture deck, the overview, or the optimization note.
"""
from __future__ import annotations

from datetime import date
from pathlib import Path

from docx import Document
from docx.shared import Pt, RGBColor

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "Query_Builder_Roles_Fuzzy_and_Stack.docx"
NAVY = RGBColor(0x1E, 0x27, 0x61)


def heading(doc: Document, text: str, level: int = 1) -> None:
    h = doc.add_heading(text, level=level)
    for run in h.runs:
        run.font.color.rgb = NAVY


def para(doc: Document, text: str) -> None:
    p = doc.add_paragraph()
    run = p.add_run(text)
    run.font.name = "Calibri"
    run.font.size = Pt(11)


def bullet(doc: Document, text: str) -> None:
    p = doc.add_paragraph(text, style="List Bullet")
    for run in p.runs:
        run.font.name = "Calibri"
        run.font.size = Pt(11)


def mono(doc: Document, text: str) -> None:
    p = doc.add_paragraph()
    run = p.add_run(text)
    run.font.name = "Consolas"
    run.font.size = Pt(9)


def table(doc: Document, headers: list[str], rows: list[list[str]]) -> None:
    grid = doc.add_table(rows=1 + len(rows), cols=len(headers))
    grid.style = "Table Grid"
    for i, header in enumerate(headers):
        cell = grid.rows[0].cells[i]
        cell.text = ""
        run = cell.paragraphs[0].add_run(header)
        run.bold = True
        run.font.name = "Calibri"
        run.font.size = Pt(10)
        run.font.color.rgb = NAVY
    for ri, row in enumerate(rows):
        for ci, value in enumerate(row):
            cell = grid.rows[ri + 1].cells[ci]
            cell.text = ""
            run = cell.paragraphs[0].add_run(value)
            run.font.name = "Calibri"
            run.font.size = Pt(10)
    doc.add_paragraph()


def build() -> None:
    doc = Document()
    normal = doc.styles["Normal"]
    normal.font.name = "Calibri"
    normal.font.size = Pt(11)

    title = doc.add_paragraph()
    run = title.add_run("Query Builder, roles, fuzzy match, and technology stack")
    run.bold = True
    run.font.name = "Calibri"
    run.font.size = Pt(22)
    run.font.color.rgb = NAVY

    sub = doc.add_paragraph()
    srun = sub.add_run(
        "How Analytics Advisor writes a query, who may run it, how a name match is scored, "
        f"and the software that does the work.  {date.today():%d %B %Y}."
    )
    srun.font.name = "Calibri"
    srun.font.size = Pt(11)
    srun.italic = True
    srun.font.color.rgb = RGBColor(0x44, 0x44, 0x44)

    para(
        doc,
        "This note answers four questions about the current application. "
        "The Query Builder compiles the officer's form into one parameterised SQL statement. "
        "Role rights are stored in the operational database and checked on every API call. "
        "A fuzzy name match is record linkage: a short blocking key plus normalized Levenshtein similarity. "
        "The browser is Next.js and React. The server is Java and Spring Boot. "
        "PostgreSQL holds users and saved queries. Presto runs cross-source and fuzzy analysis.",
    )

    heading(doc, "1. How the Query Builder writes a query", 1)
    heading(doc, "1.1 The officer fills a form, not a SQL editor", 2)
    para(
        doc,
        "Extract and Report collect choices: a source table, a destination table, up to five additional tables, "
        "the columns to show, one or more join pairs, a single join type for the primary join, filters, "
        "an optional fuzzy percentage, and grouping. The browser does not invent SQL from free text, "
        "and it does not send the question to a language model. The same choices always produce the same statement.",
    )
    para(
        doc,
        "The browser keeps that form in Query Builder state and turns it into one JSON body, RecordMatchRequest, "
        "in frontend/src/lib/queryBuilderRequestBuild.ts. Four functions do that work:",
    )
    bullet(doc, "joinPairs reads the first key pair and any extra join conditions. A blank extra row is dropped. A half-filled pair, or more than eight pairs, stops the request.")
    bullet(doc, "buildExtractRequest puts one criterion on each side of every pair, plus display columns, filters, the join type, additional tables, the result limit, and count-only.")
    bullet(doc, "buildMergedRequest adds the Report filters, grouping, and comparison groups on top of the Extract request.")
    bullet(doc, "applyJoinColumnFuzzy sets a fuzzy threshold only on a pair whose column name contains \"name\". Other pairs stay exact equality. If Ignore spaces or Case sensitive is on, each pair is sent as its own join group so those options apply only to the name pair.")
    para(
        doc,
        "The browser posts that JSON to /api/analysis/match. Save stores the same JSON. Open reads it back and fills the form. "
        "The SQL itself is written on the server.",
    )

    heading(doc, "1.2 The server compiles the JSON into one statement", 2)
    para(
        doc,
        "RecordMatchService.planMatch is the compiler. It checks that every table and column is registered, "
        "reads the live column type, and writes one parameterised statement:",
    )
    bullet(doc, "Selected attributes become the SELECT list. Display columns are not capped at eight. Each column must still belong to that side's table.")
    bullet(doc, "Each join pair becomes one condition in the ON clause. Every condition must match. They are combined with AND. Up to eight pairs are allowed, the same cap as join groups and comparison groups.")
    bullet(doc, "A pair whose column name contains \"name\" is fuzzy. Any other pair is equality. A display column named full_name does not make an m_id join fuzzy.")
    bullet(doc, "Filters are compiled by RuleCompiler. It walks a small tree. An AND or OR group becomes those words in SQL. Equals, not-equals, less than, greater than, IN, NOT IN, BETWEEN, IS NULL, and FUZZY_MATCH become that operator. The officer's value is bound as ?. It is never pasted into the SQL text.")
    bullet(doc, "Additional tables are joined in the same statement. Grouping together with extra tables is refused.")
    para(
        doc,
        "Before a comparison is written, the compiler reads the live type from information_schema and classifies it as text, number, boolean, temporal, or unknown. "
        "Presto already coerces values inside one family, such as integer with bigint. It does not coerce a date to text or a number to text. "
        "The default comparison follows the column. A date column stays a date and the typed value is bound as CAST(? AS DATE) or the matching timestamp type, so a bad date fails the query. "
        "A number compared with text is compared as a number. An explicit admin setting of text, number, or date still wins over that default. "
        "A fuzzy column that is not text is cast to VARCHAR before lower().",
    )
    para(
        doc,
        "Save and Open call planMatchForSave. That path checks the shape and does not count lakehouse rows. "
        "Run, SQL preview, and export call planMatch. That path still estimates the join and refuses an estimate above 50 million rows "
        "(SRSE_ANALYSIS_MAX_ESTIMATED_ROWS). Single-source extract has no join fan-out, so it skips that ceiling.",
    )

    heading(doc, "1.3 Where the statement runs", 2)
    para(
        doc,
        "AnalysisExecutionRouter sends a lakehouse query, a fuzzy match, or a join across sources to Presto. "
        "A query that stays on one direct database has its casts rewritten for that engine after the statement is built. "
        "SQL Server uses DATETIME2 and DATETIMEOFFSET. Oracle uses TO_DATE, TO_TIMESTAMP, and TO_TIMESTAMP_TZ. "
        "MySQL and MariaDB use DATETIME. DB2 maps timestamp with time zone to TIMESTAMP. PostgreSQL keeps the original form. "
        "VARCHAR is rewritten as well: MySQL and MariaDB use CHAR, SQL Server uses VARCHAR(MAX), and Oracle uses VARCHAR2(4000). "
        "Functions such as TRY_CAST, levenshtein_distance, approx_distinct, and UNNEST stay on Presto.",
    )
    para(
        doc,
        "A comparison the database cannot apply is HTTP 400 with code TYPE_MISMATCH. The officer sees one sentence asking for a date as YYYY-MM-DD. "
        "The failing SQL is not appended to that sentence. Other rejected queries stay HTTP 500 QUERY_FAILED and still include the failing SQL. "
        "If the analytical engine is down, the message is: The analytical engine is unavailable. Check the Presto connection, then try again.",
    )

    heading(doc, "1.4 The path from the form to SQL", 2)
    table(
        doc,
        ["Step", "What happens", "Where"],
        [
            ["1. Form", "Tables, columns, join pairs, filters, join type, fuzzy options.", "Extract and Report screens"],
            ["2. JSON", "Those choices become RecordMatchRequest. Values stay data, not SQL text.", "queryBuilderRequestBuild.ts"],
            ["3. Shape check", "Registered tables and columns, pair counts, types.", "RecordMatchService.planMatch or planMatchForSave"],
            ["4. SQL", "SELECT, ON, and WHERE with ? placeholders. Casts follow the live column type.", "RecordMatchService and RuleCompiler"],
            ["5. Engine", "Presto for the lakehouse, fuzzy, and multi-source. Direct JDBC after cast rewrite for one database.", "AnalysisExecutionRouter"],
        ],
    )

    heading(doc, "1.5 Sketch of an exact join", 2)
    para(
        doc,
        "Two exact pairs, district and member id, with a bound filter. This is the shape the compiler emits. "
        "Catalog and table names come from the registry. The status value is bound, not written into the text.",
    )
    mono(
        doc,
        "SELECT src.member_id, tgt.member_id\n"
        "FROM catalog.schema.source_table src\n"
        "INNER JOIN catalog.schema.target_table tgt\n"
        "  ON src.district_code = tgt.district_code\n"
        " AND src.m_id = tgt.m_id\n"
        "WHERE src.status = ?",
    )

    heading(doc, "1.6 Sketch of a name pair plus an exact pair", 2)
    para(
        doc,
        "name_en matched to nameinenglish at Fuzzy 80 percent, together with an exact district pair, on an inner join. "
        "The name pair contributes a three-character blocking key in the ON clause. The similarity test is in the WHERE clause "
        "because the join is inner. The bound threshold is 0.80. District stays a plain equality. "
        "If the name column is already text, the CAST AS VARCHAR shown below is omitted. If it is a number, that cast is required before lower().",
    )
    mono(
        doc,
        "ON substr(lower(src.name_en), 1, 3) = substr(lower(tgt.nameinenglish), 1, 3)\n"
        "AND src.district = tgt.district_code\n"
        "WHERE (1.0 - CAST(levenshtein_distance(lower(src.name_en), lower(tgt.nameinenglish)) AS DOUBLE)\n"
        "      / GREATEST(length(src.name_en), length(tgt.nameinenglish), 1)) >= ?",
    )

    heading(doc, "2. How role rights are maintained", 1)
    heading(doc, "2.1 Roles live in the operational database", 2)
    para(
        doc,
        "Product roles are rows in the table app_role. Liquibase creates them. A user can hold one or more roles through the user-role link. "
        "The four role codes are SUPER_ADMIN, ADMIN, OFFICER, and AUDIT_READER. "
        "The first Super Admin is created only when the user table is empty, from the bootstrap password. "
        "Unknown role codes are ignored when the session is built.",
    )
    para(
        doc,
        "RoleAuthorityMapper turns those product roles into the three authorities Spring Security checks. "
        "The mapping is fixed in code. Changing a screen's access means changing this map and SecurityConfig together.",
    )
    table(
        doc,
        ["Product role", "Authorities on the session", "What that person can do"],
        [
            ["OFFICER", "STATE_OFFICER", "Query Builder, run a match, save and open that officer's own queries."],
            ["ADMIN", "SRSE_ADMIN and STATE_OFFICER", "Officer rights, plus administration and column-metadata changes."],
            ["SUPER_ADMIN", "SRSE_ADMIN, STATE_OFFICER, and AUDIT_READ", "Admin rights, the audit log, and the only role that may grant or revoke Super Admin."],
            ["AUDIT_READER", "AUDIT_READ", "Audit log and its export. Query Builder is not included."],
        ],
    )

    heading(doc, "2.2 Every API checks the signed session", 2)
    para(
        doc,
        "Login issues a signed bearer token that carries those authorities. SessionBearerAuthenticationFilter reads the token on each request. "
        "In the RajSewadwar mode, RajSewadwarAuthenticationFilter maps the external login onto the same authorities. "
        "An invalid token is treated as anonymous. An anonymous call to a protected API is refused with HTTP 403. "
        "The browser, on 401, clears the token and returns to login. On 403 it loads the session: a required password change goes to the change-password page, "
        "a missing session goes to login, and an admin screen is refused when the session is not an administrator.",
    )
    para(doc, "SecurityConfig assigns each path to an authority. Narrow matchers are listed first:")
    table(
        doc,
        ["API", "Authority required"],
        [
            ["POST /api/auth/login, verify-otp, resend-otp, health", "Open"],
            ["POST /api/auth/change-password", "Any signed-in user"],
            ["GET /api/analysis/column-metadata", "STATE_OFFICER"],
            ["PUT and DELETE /api/analysis/column-metadata", "SRSE_ADMIN"],
            ["/api/analysis/**  (match, preview, export)", "STATE_OFFICER"],
            ["/api/saved-queries/**", "STATE_OFFICER"],
            ["/api/admin/audit and /api/admin/audit.csv", "AUDIT_READ"],
            ["/api/admin/**", "SRSE_ADMIN"],
        ],
    )
    para(
        doc,
        "Hiding a button in the browser is not the control. The server repeats the check. "
        "PasswordChangeRequiredFilter blocks the rest of the API until a required password change is done. "
        "SessionVersionValidationFilter rejects a token whose session version no longer matches the user row, so a revoked session cannot keep calling APIs.",
    )

    heading(doc, "2.3 Saved queries belong to the officer who saved them", 2)
    para(
        doc,
        "Each saved_query row stores owner_user_id. The list an officer sees is that owner's rows. "
        "Only the owner may change or delete the query. An administrator may open another user's saved query only when AdminAuthorizationService allows that administrator to manage that user. "
        "Opening still checks tables and columns under the current officer. It does not count lakehouse rows.",
    )
    para(
        doc,
        "Granting roles is also server-side. Only a Super Admin may grant or revoke SUPER_ADMIN. "
        "A Super Admin account is not manageable by a plain Admin. Audit visibility follows the same management tree: a reader sees an actor's audit row only when that reader may manage that actor, and a Super Admin sees the wider set.",
    )

    heading(doc, "2.4 Role, screen, and API", 2)
    table(
        doc,
        ["Role", "Screens", "APIs that succeed"],
        [
            ["Officer", "Query Builder, saved queries, run and export", "/api/analysis/**, /api/saved-queries/**, GET column metadata"],
            ["Admin", "Officer screens plus administration and column overrides", "Officer APIs plus /api/admin/** and column-metadata writes"],
            ["Super Admin", "Admin screens plus the audit log and role grants", "Admin APIs plus /api/admin/audit"],
            ["Audit reader", "Audit log", "/api/admin/audit and the CSV export"],
        ],
    )

    heading(doc, "3. Fuzzy match technique", 1)
    heading(doc, "3.1 When a comparison is fuzzy", 2)
    para(
        doc,
        "Fuzzy match is deterministic record linkage. It is shared by two places. "
        "A join uses it when a join-dropdown column name contains \"name\" (any letter case) and the officer set a Fuzzy percentage. "
        "A filter uses it when the operator is FUZZY_MATCH, which compares one column with a name the officer typed. "
        "An identifier pair stays equality even when a displayed column is named full_name. "
        "If several pairs share one join, only the name pair carries the percentage. A district or id pair on that same join stays exact.",
    )
    para(
        doc,
        "The percentage belongs to the pair, not to a single column. The source side of the pair must carry fuzzyThresholdPercent. "
        "A missing percentage on a name column is rejected. A percentage outside 0 to 100 is rejected. "
        "Ignore spaces and Case sensitive are options on that pair. They are not applied to an exact pair that shares the join.",
    )

    heading(doc, "3.2 Blocking key, then Levenshtein", 2)
    para(
        doc,
        "FuzzyMatchSql is the one formula used by both the join compiler and the filter compiler. "
        "Both operands are SQL the compiler already built: a column reference or a ? placeholder. Officer text is bound, never concatenated.",
    )
    para(doc, "The formula has three steps.")
    bullet(doc, "Normalise. By default each side is wrapped in lower(). If Ignore spaces is checked, spaces are removed with replace(..., ' ', ''). If Case sensitive is checked, lower() is left off.")
    bullet(doc, "Block. Only rows that share the first 3 characters are candidates: substr(normalised, 1, 3). The length is SRSE_ANALYSIS_BLOCKING_PREFIX_LEN, default 3. The equality of those prefixes is always in the ON clause, so the engine can limit the pairs it scores.")
    bullet(doc, "Score the full strings with normalized Levenshtein distance. The score is a fraction from 0 to 1. Fuzzy 80 percent means the score must be at least 0.80. The threshold is bound as a parameter (the percentage divided by 100).")
    para(doc, "The score expression is:")
    mono(
        doc,
        "(1.0 - CAST(levenshtein_distance(left, right) AS DOUBLE)\n"
        " / GREATEST(length(leftSql), length(rightSql), 1))",
    )
    para(
        doc,
        "left and right are the normalised sides. The lengths in the denominator use the original SQL operands, and the greatest of those lengths is at least 1 so an empty string does not divide by zero. "
        "A typed-name filter uses the typed value twice inside that expression, once in levenshtein_distance and once in length, so the compiler binds that value twice. "
        "A column-to-column join does not need a repeated bind.",
    )
    para(
        doc,
        "A column that is not text is cast to VARCHAR before this formula, including in the row estimate. "
        "That is why a numeric identifier used as a fuzzy blocking key becomes substr(lower(CAST(column AS VARCHAR)), 1, 3) and is never passed raw to lower().",
    )

    heading(doc, "3.3 Inner join and outer join place the score differently", 2)
    para(
        doc,
        "The blocking-key equality always stays in ON. The score test moves.",
    )
    bullet(doc, "INNER JOIN puts the score test in WHERE. Rows that fail the percentage are dropped, which is what an inner join means.")
    bullet(doc, "LEFT, RIGHT, and FULL JOIN put the score test in ON, next to the blocking key. A row on the preserved side that has no partner still appears. Putting the score in WHERE would have removed those unmatched rows.")
    para(
        doc,
        "The first typed-name fuzzy filter can also project a match score percentage on a single-source extract. Later fuzzy filters in the same tree still filter. They do not replace that first score.",
    )

    heading(doc, "3.4 What the officer sees for Fuzzy 80 on a name", 2)
    table(
        doc,
        ["Choice", "Effect in SQL"],
        [
            ["Column name contains \"name\"", "This pair is fuzzy. Other pairs on the join stay equality."],
            ["Fuzzy % 80", "Score must be >= 0.80."],
            ["Ignore spaces checked", "Spaces are removed before the prefix and the distance."],
            ["Case sensitive unchecked", "Both sides are lowercased."],
            ["Second condition, such as district", "AND equality. No percentage, no lower(), no distance."],
            ["Join type Only matching records", "Score test in WHERE."],
            ["Left, right, or full", "Score test in ON."],
        ],
    )

    heading(doc, "4. Technology stack", 1)
    para(
        doc,
        "The application is two programs. The browser talks only to the Spring API. "
        "The API talks to an operational database for identity and saved work, and to an analytical engine for the match.",
    )

    heading(doc, "4.1 Browser", 2)
    table(
        doc,
        ["Piece", "Version and role"],
        [
            ["Next.js", "16.1.1. Pages, routing, and the Query Builder screens."],
            ["React", "19.2.3. Components for Extract, Report, saved queries, and administration."],
            ["TypeScript", "5.6.3. The request builder and the form types."],
            ["Tailwind CSS", "4.1. Layout and the notice style used for handled query messages."],
            ["Zustand", "5. Query Builder form state."],
            ["HeroUI", "2.4. Controls."],
            ["TanStack Table", "8.20. Result grids."],
            ["Recharts", "2.12. Charts."],
            ["ExcelJS", "4.4. Spreadsheet export in the browser."],
            ["Vitest", "3.2. Frontend tests, including the request builder and saved-query round trip."],
        ],
    )

    heading(doc, "4.2 Application server", 2)
    table(
        doc,
        ["Piece", "Version and role"],
        [
            ["Java", "17. Language of the compiler, security, and JDBC access."],
            ["Spring Boot", "3.3.4. Web API, packaged as a runnable jar."],
            ["Spring Security", "Session checks in SecurityConfig, described in section 2."],
            ["JJWT", "0.11.5. Signed bearer tokens."],
            ["Spring Data JPA", "Users, roles, saved queries, and audit rows."],
            ["Liquibase", "Operational schema, including app_role."],
            ["Spring Mail", "Outbound mail used by account flows such as OTP delivery."],
        ],
    )

    heading(doc, "4.3 Databases and drivers", 2)
    para(
        doc,
        "Two planes stay separate. The operational plane stores the application. The analytical plane runs the officer's match.",
    )
    table(
        doc,
        ["Store", "Use"],
        [
            ["PostgreSQL 16", "Default operational database: users, roles, saved queries, audit. Local development uses this database."],
            ["PrestoDB 0.297 JDBC", "Analytical engine. Lakehouse SQL, cross-source joins, and fuzzy match, because levenshtein_distance and approx_distinct are Presto functions. The driver is Presto, not Trino."],
            ["DB2 (JCC 11.5.8)", "Supported operational database, and a direct analytical source. Direct DB2 is not sent through a Presto connector."],
            ["MySQL and MariaDB", "Direct-source drivers. Temporal casts are rewritten to DATETIME."],
            ["SQL Server", "Direct-source driver. Temporal casts become DATETIME2 and DATETIMEOFFSET."],
            ["Oracle (ojdbc11)", "Direct-source driver. Dates use TO_DATE and TO_TIMESTAMP."],
        ],
    )

    heading(doc, "4.4 How a request crosses the stack", 2)
    bullet(doc, "The officer signs in. The API checks the password, loads app_role, and returns a signed token with STATE_OFFICER, SRSE_ADMIN, or AUDIT_READ as mapped in section 2.")
    bullet(doc, "Query Builder builds RecordMatchRequest in the browser and posts it with that token.")
    bullet(doc, "SecurityConfig allows the call only when the token has STATE_OFFICER.")
    bullet(doc, "RecordMatchService and RuleCompiler write one SQL statement. TypeCoercion inserts casts from the live column type. FuzzyMatchSql adds the blocking key and the Levenshtein score when the pair is a name.")
    bullet(doc, "On Run, the 50 million row ceiling may query Presto for counts before the match. Save and Open skip that count.")
    bullet(doc, "AnalysisExecutionRouter sends the statement to Presto, or rewrites casts and sends it to one direct database.")
    bullet(doc, "Rows come back to the React screens. A handled failure is shown as a notice.")

    heading(doc, "5. Where to look in the code", 1)
    table(
        doc,
        ["Behaviour", "Code"],
        [
            ["Form to JSON, including extra join pairs and name-only fuzzy", "frontend/src/lib/queryBuilderRequestBuild.ts"],
            ["Open a saved query back into the form", "frontend/src/lib/savedQueryHydrate.ts"],
            ["Compile the match SQL and the 50 million ceiling", "RecordMatchService.planMatch and planMatchForSave"],
            ["Filter tree to WHERE, including typed-name fuzzy", "RuleCompiler"],
            ["Casts from the live column type", "TypeCoercion and SqlTypeFamily"],
            ["Blocking key and Levenshtein score", "FuzzyMatchSql"],
            ["Presto versus direct-database casts", "AnalysisExecutionRouter"],
            ["Product role to authority", "AppRole and RoleAuthorityMapper"],
            ["Which API needs which authority", "SecurityConfig"],
            ["Who may grant Super Admin, and who may see another user", "AdminAuthorizationService"],
            ["Saved query ownership", "SavedQueryService"],
        ],
    )

    doc.save(OUT)
    print(OUT)


if __name__ == "__main__":
    build()
