package gov.rajasthan.smart.srse.execution;

import gov.rajasthan.smart.srse.datasource.ExternalDataSource;
import gov.rajasthan.smart.srse.datasource.ExternalDataSourceService;
import gov.rajasthan.smart.srse.datasource.ExternalDataSourceType;
import gov.rajasthan.smart.srse.lakehouse.LakehouseIdentifiers;
import gov.rajasthan.smart.srse.lakehouse.QualifiedTable;
import gov.rajasthan.smart.srse.lakehouse.RegisteredTable;
import gov.rajasthan.smart.srse.lakehouse.RegisteredTableRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps the public catalog/schema/table request shape stable while choosing
 * Presto, a saved JDBC source, or Presto federation underneath it.
 */
@Service
public class AnalysisExecutionRouter {

    private final RegisteredTableRepository registrations;
    private final ExternalDataSourceService externalSources;
    private final JdbcTemplate presto;

    public AnalysisExecutionRouter(
            RegisteredTableRepository registrations,
            ExternalDataSourceService externalSources,
            @Qualifier("prestoJdbcTemplate") JdbcTemplate presto) {
        this.registrations = registrations;
        this.externalSources = externalSources;
        this.presto = presto;
    }

    public RoutedSql route(String sql, Collection<QualifiedTable> tables) {
        List<TableBinding> bindings = bindings(tables);
        List<TableBinding> external = bindings.stream().filter(b -> b.registration().isExternal()).toList();
        if (external.isEmpty()) {
            return new RoutedSql(sql, AnalysisExecutionRoute.presto());
        }

        boolean allExternal = external.size() == bindings.size();
        Long oneSource = external.get(0).registration().getExternalDataSourceId();
        boolean sameSource = external.stream()
                .allMatch(binding -> Objects.equals(
                        oneSource, binding.registration().getExternalDataSourceId()));

        boolean crossSource = !allExternal || !sameSource;
        if (!crossSource && !requiresPresto(sql)) {
            ExternalDataSource source = externalSources.requireActiveInternal(oneSource);
            String routed = sql;
            for (TableBinding binding : longestFirst(external)) {
                routed = replaceTable(routed, binding.logical(), directName(source, binding.registration()));
            }
            routed = adaptDirectSql(routed, source);
            return new RoutedSql(
                    routed,
                    new AnalysisExecutionRoute(AnalysisExecutionRoute.Mode.DIRECT_JDBC, oneSource));
        }

        String routed = sql;
        for (TableBinding binding : longestFirst(external)) {
            RegisteredTable row = binding.registration();
            ExternalDataSource source = externalSources.requireActiveInternal(row.getExternalDataSourceId());
            requireFederationCapable(source, crossSource);
            String federationCatalog = source.getFederationCatalog();
            if (federationCatalog == null || federationCatalog.isBlank()) {
                if (!crossSource) {
                    throw new IllegalArgumentException(
                            "This analysis uses a Presto-only function. Configure the source's Presto catalog alias "
                                    + "to run fuzzy, folded-group, or federated analysis.");
                }
                throw new IllegalArgumentException(
                        "Cross-source analysis requires a Presto catalog alias for data source “"
                                + source.getName() + "”");
            }
            LakehouseIdentifiers.requireSafe("Presto catalog alias", federationCatalog);
            String schema = LakehouseIdentifiers.requireSafe("schema", row.getSchemaName());
            String table = LakehouseIdentifiers.requireSafe("table", row.getTableName());
            routed = replaceTable(routed, binding.logical(),
                    quoteAnsi(federationCatalog) + "." + quoteAnsi(schema) + "." + quoteAnsi(table));
        }
        return new RoutedSql(
                routed,
                new AnalysisExecutionRoute(AnalysisExecutionRoute.Mode.FEDERATED_PRESTO, null));
    }

    public JdbcTemplate jdbc(AnalysisExecutionRoute route) {
        if (route.mode() == AnalysisExecutionRoute.Mode.DIRECT_JDBC) {
            return externalSources.jdbcTemplateForExecution(route.externalDataSourceId());
        }
        return presto;
    }

    /**
     * The chart wrapper is built after the match SQL is already routed.
     * Only the outer limit and identifier quotes change for a direct database.
     */
    public String adaptChartWrapper(String sql, AnalysisExecutionRoute route) {
        if (route == null || route.mode() != AnalysisExecutionRoute.Mode.DIRECT_JDBC
                || route.externalDataSourceId() == null) {
            return sql;
        }
        ExternalDataSource source = externalSources.requireActiveInternal(route.externalDataSourceId());
        String adapted = sql;
        if (source.getDatabaseType() == ExternalDataSourceType.MYSQL
                || source.getDatabaseType() == ExternalDataSourceType.MARIADB) {
            adapted = adapted.replace('"', '`');
        }
        if (source.getDatabaseType() == ExternalDataSourceType.DB2
                || source.getDatabaseType() == ExternalDataSourceType.ORACLE) {
            adapted = adapted.replaceAll("(?i)\\s+LIMIT\\s+(\\d+)\\s*$", " FETCH FIRST $1 ROWS ONLY");
        }
        if (source.getDatabaseType() == ExternalDataSourceType.SQLSERVER) {
            adapted = applySqlServerLimit(adapted);
        }
        return adapted;
    }

    public boolean containsExternal(QualifiedTable... tables) {
        for (QualifiedTable table : tables) {
            if (registration(table).isExternal()) return true;
        }
        return false;
    }

    /** Presto address used only for federated estimation/join planning. */
    public String prestoName(QualifiedTable table) {
        RegisteredTable row = registration(table);
        if (!row.isExternal()) return table.qualifiedName();
        ExternalDataSource source = externalSources.requireActiveInternal(row.getExternalDataSourceId());
        requireFederationCapable(source, true);
        if (source.getFederationCatalog() == null || source.getFederationCatalog().isBlank()) {
            throw new IllegalArgumentException(
                    "This operation requires the Presto catalog alias for data source “"
                            + source.getName() + "”");
        }
        String federationCatalog = LakehouseIdentifiers.requireSafe(
                "Presto catalog alias", source.getFederationCatalog());
        String schema = LakehouseIdentifiers.requireSafe("schema", row.getSchemaName());
        String physicalTable = LakehouseIdentifiers.requireSafe("table", row.getTableName());
        return quoteAnsi(federationCatalog) + "." + quoteAnsi(schema) + "." + quoteAnsi(physicalTable);
    }

    private static List<TableBinding> longestFirst(List<TableBinding> bindings) {
        List<TableBinding> copy = new ArrayList<>(bindings);
        copy.sort(Comparator.comparingInt((TableBinding binding) -> binding.logical().qualifiedName().length())
                .reversed());
        return copy;
    }

    private List<TableBinding> bindings(Collection<QualifiedTable> tables) {
        Map<String, TableBinding> unique = new LinkedHashMap<>();
        for (QualifiedTable table : tables) {
            unique.putIfAbsent(table.qualifiedName(), new TableBinding(table, registration(table)));
        }
        return new ArrayList<>(unique.values());
    }

    private RegisteredTable registration(QualifiedTable table) {
        return registrations.findByCatalogNameAndSchemaNameAndTableName(
                        table.catalog(), table.schema(), table.table())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Table is not registered for Analytics Advisor: " + table.qualifiedName()));
    }

    private static String replaceTable(String sql, QualifiedTable logical, String physical) {
        String token = logical.qualifiedName();
        Pattern pattern = Pattern.compile(
                "(?<![A-Za-z0-9_])" + Pattern.quote(token) + "(?![A-Za-z0-9_])");
        return pattern.matcher(sql).replaceAll(Matcher.quoteReplacement(physical));
    }

    private static String directName(ExternalDataSource source, RegisteredTable row) {
        String schema = LakehouseIdentifiers.requireSafe("schema", row.getSchemaName());
        String table = LakehouseIdentifiers.requireSafe("table", row.getTableName());
        return switch (source.getDatabaseType()) {
            case MYSQL, MARIADB -> quoteMysql(schema) + "." + quoteMysql(table);
            case SQLSERVER -> quoteBracket(schema) + "." + quoteBracket(table);
            case POSTGRESQL, DB2, ORACLE -> quoteAnsi(schema) + "." + quoteAnsi(table);
        };
    }

    private static String quoteAnsi(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    private static String quoteMysql(String name) {
        return "`" + name.replace("`", "``") + "`";
    }

    private static String quoteBracket(String name) {
        return "[" + name.replace("]", "]]") + "]";
    }

    /**
     * Never route to Presto for a database Presto has no connector for, even if an alias was somehow
     * stored: it would fail inside Presto with an opaque "catalog does not exist", and the usual advice
     * ("configure the Presto catalog alias") cannot be followed.
     */
    private static void requireFederationCapable(ExternalDataSource source, boolean crossSource) {
        ExternalDataSourceType type = source.getDatabaseType();
        if (type.federationCapable()) {
            return;
        }
        throw new IllegalArgumentException(crossSource
                ? "Cannot combine " + type.label() + " data source “" + source.getName() + "” with another source: "
                        + "PrestoDB has no " + type.label() + " connector, so a " + type.label()
                        + " database can only answer questions about its own tables."
                : "This analysis needs Presto-only features (fuzzy matching, number-vs-text comparison, "
                        + "ANY_OF or date coercion), which are not available for " + type.label() + " sources: "
                        + "PrestoDB has no " + type.label() + " connector. Compare columns of the same type, "
                        + "or use an exact match.");
    }

    private static String adaptDirectSql(String sql, ExternalDataSource source) {
        // TRY_CAST is deliberately NOT rewritten to CAST: PostgreSQL and DB2 have no TRY_CAST,
        // and a plain CAST turns one unparseable text value into a failure of the whole query
        // (the compiler uses TRY_CAST precisely so that cannot happen). requiresPresto()
        // treats it as Presto-only, so such a query needs the source's Presto catalog alias.
        String adapted = sql;
        if (requiresPresto(adapted)) {
            requireFederationCapable(source, false);
            throw new IllegalArgumentException(
                    "This analysis uses a Presto-only function. Configure the source's Presto catalog alias "
                            + "to run fuzzy, folded-group, or federated analysis.");
        }
        adapted = switch (source.getDatabaseType()) {
            case MYSQL, MARIADB -> adapted.replace(" AS VARCHAR)", " AS CHAR)");
            case SQLSERVER -> adapted.replace(" AS VARCHAR)", " AS VARCHAR(MAX))");
            case ORACLE -> adapted.replace(" AS VARCHAR)", " AS VARCHAR2(4000))");
            case POSTGRESQL, DB2 -> adapted;
        };
        adapted = adaptTemporalCasts(adapted, source.getDatabaseType());
        if (source.getDatabaseType() == ExternalDataSourceType.MYSQL
                || source.getDatabaseType() == ExternalDataSourceType.MARIADB) {
            adapted = adapted.replace('"', '`');
            adapted = stripNullOrdering(adapted);
        }
        if (source.getDatabaseType() == ExternalDataSourceType.DB2
                || source.getDatabaseType() == ExternalDataSourceType.ORACLE) {
            adapted = adapted.replaceAll("(?i)\\s+LIMIT\\s+(\\d+)\\s*$", " FETCH FIRST $1 ROWS ONLY");
        }
        if (source.getDatabaseType() == ExternalDataSourceType.SQLSERVER) {
            adapted = applySqlServerLimit(adapted);
            adapted = stripNullOrdering(adapted);
        }
        return adapted;
    }

    /**
     * The query builder emits Presto casts for a typed value ({@code CAST(? AS DATE)}).
     * Direct JDBC must say the same thing in that database's types, or a date
     * filter that worked on the lakehouse fails as soon as the source is
     * Oracle, SQL Server, MySQL, or DB2.
     *
     * <p>Longer type names are replaced first so {@code TIMESTAMP WITH TIME ZONE}
     * is not rewritten as a plain {@code TIMESTAMP}.
     */
    private static String adaptTemporalCasts(String sql, ExternalDataSourceType type) {
        return switch (type) {
            case SQLSERVER -> sql
                    .replace("CAST(? AS TIMESTAMP WITH TIME ZONE)", "CAST(? AS DATETIMEOFFSET)")
                    .replace("CAST(? AS TIMESTAMP)", "CAST(? AS DATETIME2)");
            case ORACLE -> sql
                    .replace("CAST(? AS TIMESTAMP WITH TIME ZONE)", "TO_TIMESTAMP_TZ(?, 'YYYY-MM-DD HH24:MI:SS')")
                    .replace("CAST(? AS TIMESTAMP)", "TO_TIMESTAMP(?, 'YYYY-MM-DD HH24:MI:SS')")
                    .replace("CAST(? AS DATE)", "TO_DATE(?, 'YYYY-MM-DD')")
                    .replace("CAST(? AS TIME)", "TO_TIMESTAMP(?, 'HH24:MI:SS')");
            case MYSQL, MARIADB -> sql
                    .replace("CAST(? AS TIMESTAMP WITH TIME ZONE)", "CAST(? AS DATETIME)")
                    .replace("CAST(? AS TIMESTAMP)", "CAST(? AS DATETIME)");
            case DB2 -> sql.replace("CAST(? AS TIMESTAMP WITH TIME ZONE)", "CAST(? AS TIMESTAMP)");
            case POSTGRESQL -> sql;
        };
    }

    private static String applySqlServerLimit(String sql) {
        Matcher limit = Pattern.compile("(?i)\\s+LIMIT\\s+(\\d+)\\s*$").matcher(sql);
        if (!limit.find()) {
            return sql;
        }
        String rows = limit.group(1);
        String withoutLimit = limit.replaceFirst("");
        if (withoutLimit.startsWith("SELECT DISTINCT ")) {
            return "SELECT DISTINCT TOP (" + rows + ") "
                    + withoutLimit.substring("SELECT DISTINCT ".length());
        }
        if (withoutLimit.startsWith("SELECT ")) {
            return "SELECT TOP (" + rows + ") " + withoutLimit.substring("SELECT ".length());
        }
        return withoutLimit;
    }

    private static String stripNullOrdering(String sql) {
        return sql.replace(" NULLS FIRST", "").replace(" NULLS LAST", "");
    }

    private static boolean requiresPresto(String sql) {
        String upper = sql.toUpperCase(Locale.ROOT);
        return upper.contains("TRY_CAST(")
                || upper.contains("LEVENSHTEIN_DISTANCE(")
                || upper.contains("APPROX_DISTINCT(")
                || upper.contains(" UNNEST(")
                || upper.contains("DATE_DIFF(")
                || upper.contains("REGEXP_LIKE(")
                || upper.contains("CARDINALITY(");
    }

    private record TableBinding(QualifiedTable logical, RegisteredTable registration) {}

    public record RoutedSql(String sql, AnalysisExecutionRoute route) {}
}
