package gov.rajasthan.smart.srse.datasource;

import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * JDBC source types bundled with this release. Add drivers here, never from browser uploads.
 *
 * <p>Only {@link #supported()} types may be connected. PostgreSQL, DB2, MySQL, MariaDB,
 * SQL Server, and Oracle are offered. DB2 stays direct-only because PrestoDB has no DB2 connector.
 */
public enum ExternalDataSourceType {
    POSTGRESQL("PostgreSQL", 5432, "org.postgresql.Driver", true, true),
    // PrestoDB 0.297 ships no DB2 connector (verified against its plugin directory), so a DB2 source can
    // never be given a Presto catalog alias: it answers plain single-source questions only.
    DB2("IBM DB2", 50000, "com.ibm.db2.jcc.DB2Driver", true, false),
    MYSQL("MySQL", 3306, "com.mysql.cj.jdbc.Driver", true, true),
    MARIADB("MariaDB", 3306, "org.mariadb.jdbc.Driver", true, true),
    SQLSERVER("Microsoft SQL Server", 1433, "com.microsoft.sqlserver.jdbc.SQLServerDriver", true, true),
    ORACLE("Oracle", 1521, "oracle.jdbc.OracleDriver", true, true);

    private static final Pattern SAFE_HOST = Pattern.compile("^[A-Za-z0-9._:-]+$");
    private static final Pattern SAFE_DATABASE = Pattern.compile("^[A-Za-z0-9._-]+$");

    private final String label;
    private final int defaultPort;
    private final String driverClassName;
    private final boolean supported;
    private final boolean federationCapable;

    ExternalDataSourceType(
            String label, int defaultPort, String driverClassName, boolean supported, boolean federationCapable) {
        this.federationCapable = federationCapable;
        this.label = label;
        this.defaultPort = defaultPort;
        this.driverClassName = driverClassName;
        this.supported = supported;
    }

    /**
     * Whether Presto has a connector for this database, i.e. whether a catalog alias can exist. False means
     * fuzzy matching, number-vs-text comparison, ANY_OF and any join to another source can never run.
     */
    public boolean federationCapable() {
        return federationCapable;
    }

    /** False when a bundled type must stay hidden and be refused on create. */
    public boolean supported() {
        return supported;
    }

    public String label() {
        return label;
    }

    public int defaultPort() {
        return defaultPort;
    }

    public String driverClassName() {
        return driverClassName;
    }

    /**
     * When {@code host} is a full {@code jdbc:…} URL, it is validated and returned as-is
     * (port/database/ssl are ignored). Otherwise builds the URL from parts.
     */
    public static String resolveJdbcUrl(
            ExternalDataSourceType type, String host, Integer port, String database, boolean ssl) {
        String trimmed = host == null ? "" : host.trim();
        if (trimmed.regionMatches(true, 0, "jdbc:", 0, 5)) {
            return requireSafeJdbcUrl(trimmed);
        }
        return type.jdbcUrl(host, port, database, ssl);
    }

    private static String requireSafeJdbcUrl(String url) {
        if (url.length() > 2048 || url.indexOf('\n') >= 0 || url.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("JDBC URL is not valid");
        }
        if (!url.regionMatches(true, 0, "jdbc:", 0, 5)) {
            throw new IllegalArgumentException("JDBC URL must start with jdbc:");
        }
        return url;
    }

    public String jdbcUrl(String host, Integer port, String database, boolean ssl) {
        String safeHost = required("Host", host);
        String safeDatabase = required("Database", database);
        if (!SAFE_HOST.matcher(safeHost).matches()) {
            throw new IllegalArgumentException("Host contains unsupported characters");
        }
        if (!SAFE_DATABASE.matcher(safeDatabase).matches()) {
            throw new IllegalArgumentException("Database contains unsupported characters");
        }
        int safePort = port == null || port == 0 ? defaultPort : port;
        if (safePort < 1 || safePort > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        }
        return switch (this) {
            case POSTGRESQL -> "jdbc:postgresql://" + safeHost + ":" + safePort + "/" + safeDatabase
                    + (ssl ? "?sslmode=require" : "");
            case DB2 -> "jdbc:db2://" + safeHost + ":" + safePort + "/" + safeDatabase
                    + (ssl ? ":sslConnection=true;" : "");
            case MYSQL -> "jdbc:mysql://" + safeHost + ":" + safePort + "/" + safeDatabase
                    + (ssl ? "?sslMode=REQUIRED" : "?sslMode=DISABLED");
            case MARIADB -> "jdbc:mariadb://" + safeHost + ":" + safePort + "/" + safeDatabase
                    + (ssl ? "?sslMode=verify-full" : "");
            case SQLSERVER -> "jdbc:sqlserver://" + safeHost + ":" + safePort
                    + ";databaseName=" + safeDatabase
                    + (ssl ? ";encrypt=true;trustServerCertificate=false" : ";encrypt=false");
            case ORACLE -> "jdbc:oracle:thin:@//" + safeHost + ":" + safePort + "/" + safeDatabase
                    + (ssl ? "?oracle.net.ssl_server_dn_match=true" : "");
        };
    }

    public static ExternalDataSourceType parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Database type is required");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unsupported database type: " + value);
        }
    }

    /** {@link #parse} plus the 0.13 gate — the one entry point for a new connection. */
    public static ExternalDataSourceType parseSupported(String value) {
        ExternalDataSourceType type = parse(value);
        if (!type.supported()) {
            String offered = Arrays.stream(values())
                    .filter(ExternalDataSourceType::supported)
                    .map(ExternalDataSourceType::label)
                    .collect(Collectors.joining(", "));
            throw new IllegalArgumentException(
                    type.label() + " connections are not supported in this release. Supported: " + offered + ".");
        }
        return type;
    }

    private static String required(String label, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }
}
