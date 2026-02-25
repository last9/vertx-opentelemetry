package io.last9.tracing.otel.v3;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.reactivex.Completable;
import io.reactivex.Maybe;
import io.reactivex.Single;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import io.vertx.ext.sql.ResultSet;
import io.vertx.ext.sql.UpdateResult;
import io.vertx.reactivex.ext.sql.SQLClient;
import io.vertx.reactivex.ext.sql.SQLConnection;
import io.vertx.reactivex.ext.sql.SQLRowStream;

/**
 * A drop-in replacement for Vert.x 3 {@link SQLClient} that automatically wraps every
 * database operation with an OpenTelemetry CLIENT span.
 *
 * <p>Vert.x 3 has no {@code VertxTracer} SPI for database clients, so SQL queries produce
 * no spans by default. {@code TracedSQLClient} solves this by intercepting all {@code rx*}
 * methods and wrapping them with {@link DbTracing}.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Instead of: SQLClient client = JDBCClient.createShared(vertx, config);
 * SQLClient client = TracedSQLClient.wrap(
 *         JDBCClient.createShared(vertx, config), "mysql", "orders_db");
 *
 * // Every query automatically gets a CLIENT span:
 * client.rxQueryWithParams("SELECT * FROM orders WHERE id = ?", params)
 *     .subscribe(resultSet -> { ... });
 * }</pre>
 *
 * <p>Connections obtained via {@link #rxGetConnection()} are also auto-traced —
 * they return a {@link TracedSQLConnection} that instruments all connection-level
 * operations.
 *
 * @see DbTracing
 * @see TracedSQLConnection
 */
public class TracedSQLClient extends SQLClient {

    private final SQLClient delegate;
    private final DbTracing db;

    TracedSQLClient(SQLClient delegate, DbTracing db) {
        super(delegate.getDelegate());
        this.delegate = delegate;
        this.db = db;
    }

    /**
     * Wraps an existing {@link SQLClient} with tracing using {@link GlobalOpenTelemetry}.
     * The {@code db.name} attribute is omitted from spans.
     *
     * @param client    the existing SQLClient to wrap
     * @param dbSystem  the database system identifier (e.g., "mysql", "postgresql")
     * @return a SQLClient that auto-instruments all queries with CLIENT spans
     */
    public static TracedSQLClient wrap(SQLClient client, String dbSystem) {
        return wrap(client, dbSystem, null, GlobalOpenTelemetry.get());
    }

    /**
     * Wraps an existing {@link SQLClient} with tracing using {@link GlobalOpenTelemetry}.
     *
     * @param client    the existing SQLClient to wrap
     * @param dbSystem  the database system identifier (e.g., "mysql", "postgresql")
     * @param dbName    the database name (e.g., "orders_db"); may be {@code null} to omit
     * @return a SQLClient that auto-instruments all queries with CLIENT spans
     */
    public static TracedSQLClient wrap(SQLClient client, String dbSystem, String dbName) {
        return wrap(client, dbSystem, dbName, GlobalOpenTelemetry.get());
    }

    /**
     * Wraps an existing {@link SQLClient} with tracing using the supplied {@link OpenTelemetry}.
     * The {@code db.name} attribute is omitted from spans. Useful in tests.
     *
     * @param client        the existing SQLClient to wrap
     * @param dbSystem      the database system identifier
     * @param openTelemetry the OpenTelemetry instance to use
     * @return a SQLClient that auto-instruments all queries with CLIENT spans
     */
    public static TracedSQLClient wrap(SQLClient client, String dbSystem,
                                       OpenTelemetry openTelemetry) {
        return wrap(client, dbSystem, null, openTelemetry);
    }

    /**
     * Wraps an existing {@link SQLClient} with tracing using the supplied {@link OpenTelemetry}.
     * Useful in tests.
     *
     * @param client        the existing SQLClient to wrap
     * @param dbSystem      the database system identifier
     * @param dbName        the database name; may be {@code null} to omit
     * @param openTelemetry the OpenTelemetry instance to use
     * @return a SQLClient that auto-instruments all queries with CLIENT spans
     */
    public static TracedSQLClient wrap(SQLClient client, String dbSystem, String dbName,
                                       OpenTelemetry openTelemetry) {
        DbTracing db = DbTracing.create(dbSystem, dbName, openTelemetry);
        return new TracedSQLClient(client, db);
    }

    // ---- Query ----

    @Override
    public Single<ResultSet> rxQuery(String sql) {
        return db.traceSingle(sql, () -> delegate.rxQuery(sql));
    }

    @Override
    public Single<ResultSet> rxQueryWithParams(String sql, JsonArray params) {
        return db.traceSingle(sql, () -> delegate.rxQueryWithParams(sql, params));
    }

    @Override
    public Single<SQLRowStream> rxQueryStream(String sql) {
        return db.traceSingle(sql, () -> delegate.rxQueryStream(sql));
    }

    @Override
    public Single<SQLRowStream> rxQueryStreamWithParams(String sql, JsonArray params) {
        return db.traceSingle(sql, () -> delegate.rxQueryStreamWithParams(sql, params));
    }

    @Override
    public Maybe<JsonArray> rxQuerySingle(String sql) {
        return db.traceMaybe(sql, () -> delegate.rxQuerySingle(sql));
    }

    @Override
    public Maybe<JsonArray> rxQuerySingleWithParams(String sql, JsonArray params) {
        return db.traceMaybe(sql, () -> delegate.rxQuerySingleWithParams(sql, params));
    }

    // ---- Update ----

    @Override
    public Single<UpdateResult> rxUpdate(String sql) {
        return db.traceSingle(sql, () -> delegate.rxUpdate(sql));
    }

    @Override
    public Single<UpdateResult> rxUpdateWithParams(String sql, JsonArray params) {
        return db.traceSingle(sql, () -> delegate.rxUpdateWithParams(sql, params));
    }

    // ---- Call (stored procedures) ----

    @Override
    public Single<ResultSet> rxCall(String sql) {
        return db.traceSingle(sql, () -> delegate.rxCall(sql));
    }

    @Override
    public Single<ResultSet> rxCallWithParams(String sql, JsonArray params, JsonArray outputs) {
        return db.traceSingle(sql, () -> delegate.rxCallWithParams(sql, params, outputs));
    }

    // ---- Connection ----

    @Override
    public Single<SQLConnection> rxGetConnection() {
        return delegate.rxGetConnection()
                .map(conn -> new TracedSQLConnection(conn, db));
    }

    // ---- Lifecycle (no tracing) ----

    @Override
    public Completable rxClose() {
        return delegate.rxClose();
    }

    @Override
    public void close() {
        delegate.close();
    }

    @Override
    public void close(Handler<AsyncResult<Void>> handler) {
        delegate.close(handler);
    }
}
