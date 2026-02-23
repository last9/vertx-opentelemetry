package io.last9.tracing.otel.v3;

import io.reactivex.Completable;
import io.reactivex.Maybe;
import io.reactivex.Single;
import io.vertx.core.json.JsonArray;
import io.vertx.ext.sql.ResultSet;
import io.vertx.ext.sql.UpdateResult;
import io.vertx.reactivex.ext.sql.SQLConnection;
import io.vertx.reactivex.ext.sql.SQLRowStream;

import java.util.List;

/**
 * Auto-traced wrapper for {@link SQLConnection}. Created internally by
 * {@link TracedSQLClient#rxGetConnection()} — not intended for direct construction.
 *
 * <p>Every query, update, and call on this connection is wrapped with an OpenTelemetry
 * CLIENT span via {@link DbTracing}. Transaction operations ({@code COMMIT},
 * {@code ROLLBACK}) also produce spans.
 *
 * @see TracedSQLClient
 * @see DbTracing
 */
class TracedSQLConnection extends SQLConnection {

    private final SQLConnection delegate;
    private final DbTracing db;

    TracedSQLConnection(SQLConnection delegate, DbTracing db) {
        super(delegate.getDelegate());
        this.delegate = delegate;
        this.db = db;
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

    // ---- Execute ----

    @Override
    public Completable rxExecute(String sql) {
        return db.traceCompletable(sql, () -> delegate.rxExecute(sql));
    }

    // ---- Batch ----

    @Override
    public Single<List<Integer>> rxBatch(List<String> sqlStatements) {
        return db.traceSingle("BATCH (" + sqlStatements.size() + " statements)",
                () -> delegate.rxBatch(sqlStatements));
    }

    @Override
    public Single<List<Integer>> rxBatchWithParams(String sql, List<JsonArray> params) {
        return db.traceSingle(sql + " (batch " + params.size() + ")",
                () -> delegate.rxBatchWithParams(sql, params));
    }

    @Override
    public Single<List<Integer>> rxBatchCallableWithParams(String sql,
                                                           List<JsonArray> inArgs,
                                                           List<JsonArray> outArgs) {
        return db.traceSingle(sql + " (batch callable " + inArgs.size() + ")",
                () -> delegate.rxBatchCallableWithParams(sql, inArgs, outArgs));
    }

    // ---- Transaction ----

    @Override
    public Completable rxCommit() {
        return db.traceCompletable("COMMIT", () -> delegate.rxCommit());
    }

    @Override
    public Completable rxRollback() {
        return db.traceCompletable("ROLLBACK", () -> delegate.rxRollback());
    }

    // ---- Lifecycle (no tracing) ----

    @Override
    public Completable rxSetAutoCommit(boolean autoCommit) {
        return delegate.rxSetAutoCommit(autoCommit);
    }

    @Override
    public Completable rxClose() {
        return delegate.rxClose();
    }

    @Override
    public void close() {
        delegate.close();
    }
}
