package io.last9.tracing.otel.v3;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.reactivex.Single;
import io.vertx.reactivex.mysqlclient.MySQLPool;
import io.vertx.reactivex.sqlclient.Row;
import io.vertx.reactivex.sqlclient.RowSet;
import io.vertx.reactivex.sqlclient.Tuple;

/**
 * A tracing wrapper for the Vert.x 3 reactive {@link MySQLPool}.
 *
 * <p>Unlike the legacy {@link TracedSQLClient} (which wraps the older
 * {@code io.vertx.ext.sql.SQLClient} JDBC-style API), this class wraps the newer
 * reactive SQL client API introduced in Vert.x 3.8+ ({@code vertx-mysql-client}).
 * Each query or prepared-query execution is wrapped with an OpenTelemetry CLIENT span
 * via {@link DbTracing}.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * MySQLPool pool = MySQLPool.pool(vertx, connectOptions, poolOptions);
 * TracedMySQLClient traced = TracedMySQLClient.wrap(pool, "orders_db");
 *
 * // Every query automatically gets a CLIENT span:
 * traced.query("SELECT * FROM orders WHERE id = ?")
 *     .subscribe(rows -> { ... });
 *
 * // Parameterised query:
 * traced.preparedQuery("SELECT * FROM orders WHERE id = ?", Tuple.of(42))
 *     .subscribe(rows -> { ... });
 * }</pre>
 *
 * <p>Use {@link #unwrap()} to access pool-level operations not covered here
 * (e.g., transactions, explicit connection management).
 *
 * @see TracedSQLClient for the legacy {@code io.vertx.ext.sql.SQLClient} wrapper
 * @see DbTracing
 */
public final class TracedMySQLClient {

    private final MySQLPool pool;
    private final DbTracing db;

    private TracedMySQLClient(MySQLPool pool, DbTracing db) {
        this.pool = pool;
        this.db = db;
    }

    /**
     * Wraps a {@link MySQLPool} with tracing using {@link GlobalOpenTelemetry}.
     * The {@code db.name} span attribute is omitted.
     *
     * @param pool the MySQLPool to wrap
     * @return a tracing wrapper
     */
    public static TracedMySQLClient wrap(MySQLPool pool) {
        return wrap(pool, null, GlobalOpenTelemetry.get());
    }

    /**
     * Wraps a {@link MySQLPool} with tracing using {@link GlobalOpenTelemetry}.
     *
     * @param pool   the MySQLPool to wrap
     * @param dbName the database name (e.g., {@code "orders_db"}); may be {@code null} to omit
     * @return a tracing wrapper
     */
    public static TracedMySQLClient wrap(MySQLPool pool, String dbName) {
        return wrap(pool, dbName, GlobalOpenTelemetry.get());
    }

    /**
     * Wraps a {@link MySQLPool} with tracing using the supplied {@link OpenTelemetry} instance.
     * Useful in tests that construct their own {@code OpenTelemetrySdk}.
     *
     * @param pool          the MySQLPool to wrap
     * @param dbName        the database name; may be {@code null} to omit
     * @param openTelemetry the OpenTelemetry instance to use
     * @return a tracing wrapper
     */
    public static TracedMySQLClient wrap(MySQLPool pool, String dbName, OpenTelemetry openTelemetry) {
        DbTracing db = DbTracing.create("mysql", dbName, openTelemetry);
        return new TracedMySQLClient(pool, db);
    }

    /**
     * Executes a simple (non-parameterised) SQL query with a CLIENT span.
     *
     * @param sql the SQL statement to execute
     * @return a Single that emits the result rows
     */
    public Single<RowSet<Row>> query(String sql) {
        return db.traceSingle(sql, () -> pool.query(sql).rxExecute());
    }

    /**
     * Executes a parameterised prepared query with a CLIENT span.
     *
     * @param sql  the SQL statement (with {@code ?} placeholders)
     * @param args the bind parameters
     * @return a Single that emits the result rows
     */
    public Single<RowSet<Row>> preparedQuery(String sql, Tuple args) {
        return db.traceSingle(sql, () -> pool.preparedQuery(sql).rxExecute(args));
    }

    /**
     * Executes a prepared query with no bind parameters and a CLIENT span.
     *
     * @param sql the SQL statement
     * @return a Single that emits the result rows
     */
    public Single<RowSet<Row>> preparedQuery(String sql) {
        return db.traceSingle(sql, () -> pool.preparedQuery(sql).rxExecute());
    }

    /**
     * Returns the underlying {@link MySQLPool} for operations not covered by this wrapper
     * (e.g., transactions, explicit connections, bulk operations).
     *
     * @return the underlying pool
     */
    public MySQLPool unwrap() {
        return pool;
    }
}
