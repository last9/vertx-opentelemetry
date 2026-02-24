package io.last9.tracing.otel.v4;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.reactivex.rxjava3.core.Single;
import io.vertx.rxjava3.sqlclient.Pool;
import io.vertx.rxjava3.sqlclient.Row;
import io.vertx.rxjava3.sqlclient.RowSet;
import io.vertx.rxjava3.sqlclient.Tuple;

/**
 * A tracing wrapper for Vert.x 4 reactive SQL pools (PostgreSQL, MySQL, etc.).
 *
 * <p>Wraps any {@link Pool} — including {@code io.vertx.rxjava3.pgclient.PgPool} and
 * {@code io.vertx.rxjava3.mysqlclient.MySQLPool} — and adds an OpenTelemetry CLIENT span
 * to every query or prepared-query execution.
 *
 * <h2>Usage — direct pool</h2>
 * <pre>{@code
 * PgPool pool = PgPool.pool(vertx, connectOptions, poolOptions);
 * TracedDBPool traced = TracedDBPool.wrap(pool, "postgresql", "orders_db");
 *
 * // Every query automatically gets a CLIENT span:
 * traced.query("SELECT * FROM orders WHERE id = $1")
 *     .subscribe(rows -> { ... });
 *
 * // Parameterised query:
 * traced.preparedQuery("SELECT * FROM orders WHERE id = $1", Tuple.of(42))
 *     .subscribe(rows -> { ... });
 * }</pre>
 *
 * <h2>Usage — with a custom PostgresClient (master/slave pattern)</h2>
 * <pre>{@code
 * PostgresClient pgClient = PostgresClient.create(vertx);
 * pgClient.rxConnect()
 *     .doOnComplete(() -> {
 *         TracedDBPool tracedMaster = TracedDBPool.wrap(pgClient.getMasterPool(), "postgresql", "mydb");
 *         TracedDBPool tracedSlave  = TracedDBPool.wrap(pgClient.getSlavePool(),  "postgresql", "mydb");
 *     })
 *     .subscribe();
 * }</pre>
 *
 * <p>Use {@link #unwrap()} to access pool-level operations not covered here
 * (e.g., transactions, explicit connection management).
 *
 * @see DbTracing for wrapping arbitrary database operations in CLIENT spans
 */
public final class TracedDBPool {

    private final Pool pool;
    private final DbTracing db;

    private TracedDBPool(Pool pool, DbTracing db) {
        this.pool = pool;
        this.db = db;
    }

    /**
     * Wraps a pool using {@link GlobalOpenTelemetry}. The {@code db.name} attribute is omitted.
     *
     * @param pool     the pool to wrap (PgPool, MySQLPool, or any other reactive Pool)
     * @param dbSystem the database system identifier (e.g. {@code "postgresql"}, {@code "mysql"})
     * @return a tracing wrapper
     */
    public static TracedDBPool wrap(Pool pool, String dbSystem) {
        return wrap(pool, dbSystem, null, GlobalOpenTelemetry.get());
    }

    /**
     * Wraps a pool using {@link GlobalOpenTelemetry}.
     *
     * @param pool     the pool to wrap
     * @param dbSystem the database system identifier (e.g. {@code "postgresql"}, {@code "mysql"})
     * @param dbName   the database name shown in the {@code db.name} span attribute
     * @return a tracing wrapper
     */
    public static TracedDBPool wrap(Pool pool, String dbSystem, String dbName) {
        return wrap(pool, dbSystem, dbName, GlobalOpenTelemetry.get());
    }

    /**
     * Wraps a pool using the supplied {@link OpenTelemetry} instance.
     * Useful in tests that construct their own {@code OpenTelemetrySdk}.
     *
     * @param pool          the pool to wrap
     * @param dbSystem      the database system identifier
     * @param dbName        the database name; may be {@code null} to omit
     * @param openTelemetry the OpenTelemetry instance to use
     * @return a tracing wrapper
     */
    public static TracedDBPool wrap(Pool pool, String dbSystem, String dbName, OpenTelemetry openTelemetry) {
        DbTracing db = DbTracing.create(dbSystem, dbName, openTelemetry);
        return new TracedDBPool(pool, db);
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
     * @param sql  the SQL statement
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
     * Returns the underlying {@link Pool} for operations not covered by this wrapper
     * (e.g., transactions, explicit connections, bulk operations).
     *
     * @return the underlying pool
     */
    public Pool unwrap() {
        return pool;
    }
}
