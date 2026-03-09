package io.last9.tracing.otel.v3;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts a low-cardinality span name from a SQL statement, matching the
 * OTel Java agent convention: {@code {operation} {db.name}.{table}}.
 *
 * <p>Examples:
 * <ul>
 *   <li>{@code SELECT * FROM users WHERE id = ?} → {@code SELECT users}</li>
 *   <li>{@code INSERT INTO orders (id) VALUES (?)} → {@code INSERT orders}</li>
 *   <li>{@code SHOW VARIABLES} → {@code SHOW}</li>
 * </ul>
 *
 * <p>When a {@code dbName} is provided, the table is qualified:
 * {@code SELECT cep_core.user_column_preferences}.
 */
public final class SqlSpanName {

    private SqlSpanName() {}

    private static final Set<String> SQL_KEYWORDS = Set.of(
            "SELECT", "INSERT", "UPDATE", "DELETE", "REPLACE",
            "CREATE", "DROP", "ALTER", "TRUNCATE",
            "SHOW", "SET", "COMMIT", "ROLLBACK", "BEGIN",
            "CALL", "EXECUTE", "EXPLAIN", "DESCRIBE", "USE", "GRANT", "REVOKE");

    private static final Set<String> FROM_OPS = Set.of("SELECT", "DELETE");
    private static final Set<String> INTO_OPS = Set.of("INSERT", "REPLACE");
    // Matches a SQL identifier: unquoted word, or backtick/double-quote quoted
    private static final Pattern TABLE_PATTERN = Pattern.compile(
            "(`[^`]+`|\"[^\"]+\"|[A-Za-z_][A-Za-z0-9_.]*)"
    );

    /**
     * Returns true if the string starts with a known SQL keyword.
     */
    public static boolean looksLikeSql(String s) {
        if (s == null || s.isBlank()) return false;
        String[] tokens = tokenize(s);
        return tokens.length > 0 && SQL_KEYWORDS.contains(tokens[0].toUpperCase(Locale.ROOT));
    }

    /**
     * Build a span name from a SQL statement, without db name qualification.
     *
     * @param sql the SQL statement
     * @return span name like {@code SELECT users} or {@code SHOW}
     */
    public static String fromSql(String sql) {
        return fromSql(sql, null);
    }

    /**
     * Build a span name from a SQL statement, optionally qualified with db name.
     *
     * @param sql    the SQL statement
     * @param dbName the database/schema name, or null to omit
     * @return span name like {@code SELECT cep_core.users} or {@code SHOW}
     */
    public static String fromSql(String sql, String dbName) {
        if (sql == null || sql.isBlank()) {
            return "SQL";
        }

        String[] tokens = tokenize(sql);
        if (tokens.length == 0) {
            return "SQL";
        }

        String operation = tokens[0].toUpperCase(Locale.ROOT);
        String table = extractTable(tokens, operation);

        if (table == null) {
            return operation;
        }

        // Strip backticks/quotes from table name
        table = unquote(table);

        if (dbName != null && !dbName.isEmpty() && !table.contains(".")) {
            return operation + " " + dbName + "." + table;
        }
        return operation + " " + table;
    }

    private static String extractTable(String[] tokens, String operation) {
        if (FROM_OPS.contains(operation)) {
            return findWordAfter(tokens, "FROM");
        }
        if (INTO_OPS.contains(operation)) {
            return findWordAfter(tokens, "INTO");
        }
        if ("UPDATE".equals(operation) && tokens.length >= 2) {
            return tokens[1];
        }
        if ("CREATE".equals(operation) || "DROP".equals(operation) || "ALTER".equals(operation)
                || "TRUNCATE".equals(operation)) {
            // CREATE TABLE foo, DROP INDEX bar, ALTER TABLE baz
            return tokens.length >= 3 ? tokens[2] : null;
        }
        return null;
    }

    private static String findWordAfter(String[] tokens, String keyword) {
        for (int i = 0; i < tokens.length - 1; i++) {
            if (tokens[i].equalsIgnoreCase(keyword)) {
                return tokens[i + 1];
            }
        }
        return null;
    }

    private static String[] tokenize(String sql) {
        // Split on whitespace, commas, parens — keep just identifiers and keywords
        return sql.trim().split("[\\s,()]+");
    }

    private static String unquote(String identifier) {
        if (identifier.length() >= 2) {
            char first = identifier.charAt(0);
            char last = identifier.charAt(identifier.length() - 1);
            if ((first == '`' && last == '`') || (first == '"' && last == '"')) {
                return identifier.substring(1, identifier.length() - 1);
            }
        }
        return identifier;
    }
}
