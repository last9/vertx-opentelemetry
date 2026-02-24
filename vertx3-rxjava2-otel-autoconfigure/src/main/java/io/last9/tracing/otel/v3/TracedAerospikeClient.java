package io.last9.tracing.otel.v3;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.AerospikeException;
import com.aerospike.client.BatchRead;
import com.aerospike.client.BatchRecord;
import com.aerospike.client.BatchResults;
import com.aerospike.client.Bin;
import com.aerospike.client.IAerospikeClient;
import com.aerospike.client.Key;
import com.aerospike.client.Operation;
import com.aerospike.client.Record;
import com.aerospike.client.ScanCallback;
import com.aerospike.client.Value;
import com.aerospike.client.cluster.Node;
import com.aerospike.client.policy.BatchDeletePolicy;
import com.aerospike.client.policy.BatchPolicy;
import com.aerospike.client.policy.BatchUDFPolicy;
import com.aerospike.client.policy.BatchWritePolicy;
import com.aerospike.client.policy.Policy;
import com.aerospike.client.policy.QueryPolicy;
import com.aerospike.client.policy.ScanPolicy;
import com.aerospike.client.policy.WritePolicy;
import com.aerospike.client.query.PartitionFilter;
import com.aerospike.client.query.RecordSet;
import com.aerospike.client.query.Statement;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;

import java.util.List;

/**
 * A tracing wrapper for the Aerospike Java client that automatically adds
 * OpenTelemetry CLIENT spans to every synchronous data-plane operation.
 *
 * <p>Wraps an {@link AerospikeClient} (or any {@link IAerospikeClient}) and exposes
 * the same data-plane API with spans added via {@link DbTracing}. Use {@link #unwrap()}
 * to access lifecycle and admin methods not covered here.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * AerospikeClient client = new AerospikeClient("localhost", 3000);
 * TracedAerospikeClient traced = TracedAerospikeClient.wrap(client, "my-namespace");
 *
 * // Every data-plane call (get, put, delete, exists, operate, query, scanAll)
 * // automatically gets a CLIENT span:
 * Record record = traced.get(null, new Key("my-namespace", "users", "user:123"));
 * traced.put(null, key, new Bin("name", "Alice"));
 * }</pre>
 *
 * @see DbTracing
 */
public final class TracedAerospikeClient {

    private final IAerospikeClient delegate;
    private final DbTracing db;

    private TracedAerospikeClient(IAerospikeClient delegate, DbTracing db) {
        this.delegate = delegate;
        this.db = db;
    }

    // ---- Factory methods ----

    /**
     * Wraps an {@link AerospikeClient} with tracing using {@link GlobalOpenTelemetry}.
     * The {@code db.name} attribute is omitted from spans.
     *
     * @param client the Aerospike client to wrap
     * @return a tracing wrapper
     */
    public static TracedAerospikeClient wrap(AerospikeClient client) {
        return create(client, null, GlobalOpenTelemetry.get());
    }

    /**
     * Wraps an {@link AerospikeClient} with tracing using {@link GlobalOpenTelemetry}.
     *
     * @param client      the Aerospike client to wrap
     * @param dbNamespace the Aerospike namespace used as {@code db.name}; may be {@code null}
     * @return a tracing wrapper
     */
    public static TracedAerospikeClient wrap(AerospikeClient client, String dbNamespace) {
        return create(client, dbNamespace, GlobalOpenTelemetry.get());
    }

    /**
     * Wraps an {@link AerospikeClient} with tracing using the supplied {@link OpenTelemetry}.
     *
     * @param client        the Aerospike client to wrap
     * @param dbNamespace   the Aerospike namespace; may be {@code null} to omit
     * @param openTelemetry the OpenTelemetry instance to use
     * @return a tracing wrapper
     */
    public static TracedAerospikeClient wrap(AerospikeClient client, String dbNamespace,
                                             OpenTelemetry openTelemetry) {
        return create(client, dbNamespace, openTelemetry);
    }

    /**
     * Wraps an {@link IAerospikeClient} with tracing using the supplied {@link OpenTelemetry}.
     * Accepts any {@code IAerospikeClient} implementation — useful in tests that use stubs.
     *
     * @param client        the Aerospike client to wrap
     * @param dbNamespace   the Aerospike namespace; may be {@code null} to omit
     * @param openTelemetry the OpenTelemetry instance to use
     * @return a tracing wrapper
     */
    public static TracedAerospikeClient wrap(IAerospikeClient client, String dbNamespace,
                                             OpenTelemetry openTelemetry) {
        return create(client, dbNamespace, openTelemetry);
    }

    /**
     * Wraps an {@link IAerospikeClient} with tracing using {@link GlobalOpenTelemetry}.
     * The {@code db.name} attribute is omitted from spans.
     *
     * @param client        the Aerospike client to wrap
     * @param openTelemetry the OpenTelemetry instance to use
     * @return a tracing wrapper
     */
    public static TracedAerospikeClient wrap(IAerospikeClient client, OpenTelemetry openTelemetry) {
        return create(client, null, openTelemetry);
    }

    private static TracedAerospikeClient create(IAerospikeClient client, String dbNamespace,
                                                 OpenTelemetry openTelemetry) {
        DbTracing db = DbTracing.create("aerospike", dbNamespace, openTelemetry);
        return new TracedAerospikeClient(client, db);
    }

    // ---- Single-key read ----

    /** Gets a record for the given key. */
    public Record get(Policy policy, Key key) throws AerospikeException {
        return db.traceSync(op("GET", key), () -> delegate.get(policy, key));
    }

    /** Gets specific bins for the given key. */
    public Record get(Policy policy, Key key, String... binNames) throws AerospikeException {
        return db.traceSync(op("GET", key), () -> delegate.get(policy, key, binNames));
    }

    /** Gets a record header (generation and expiration only) for the given key. */
    public Record getHeader(Policy policy, Key key) throws AerospikeException {
        return db.traceSync(op("GET_HEADER", key), () -> delegate.getHeader(policy, key));
    }

    // ---- Batch read ----

    /** Reads multiple records in a batch. */
    public Record[] get(BatchPolicy policy, Key[] keys) throws AerospikeException {
        return db.traceSync(batchOp("GET", keys), () -> delegate.get(policy, keys));
    }

    /** Reads specific bins for multiple records in a batch. */
    public Record[] get(BatchPolicy policy, Key[] keys, String... binNames) throws AerospikeException {
        return db.traceSync(batchOp("GET", keys), () -> delegate.get(policy, keys, binNames));
    }

    /** Reads multiple records with operations in a batch. */
    public Record[] get(BatchPolicy policy, Key[] keys, Operation... ops) throws AerospikeException {
        return db.traceSync(batchOp("GET", keys), () -> delegate.get(policy, keys, ops));
    }

    /** Reads a list of {@link BatchRead} records. */
    public boolean get(BatchPolicy policy, List<BatchRead> records) throws AerospikeException {
        return db.traceSync("GET (" + records.size() + " keys)", () -> delegate.get(policy, records));
    }

    /** Gets record headers for multiple keys in a batch. */
    public Record[] getHeader(BatchPolicy policy, Key[] keys) throws AerospikeException {
        return db.traceSync(batchOp("GET_HEADER", keys), () -> delegate.getHeader(policy, keys));
    }

    // ---- Write ----

    /** Writes a record. */
    public void put(WritePolicy policy, Key key, Bin... bins) throws AerospikeException {
        db.traceSync(op("PUT", key), () -> { delegate.put(policy, key, bins); return null; });
    }

    /** Appends bin values to existing record bin values. */
    public void append(WritePolicy policy, Key key, Bin... bins) throws AerospikeException {
        db.traceSync(op("APPEND", key), () -> { delegate.append(policy, key, bins); return null; });
    }

    /** Prepends bin values to existing record bin values. */
    public void prepend(WritePolicy policy, Key key, Bin... bins) throws AerospikeException {
        db.traceSync(op("PREPEND", key), () -> { delegate.prepend(policy, key, bins); return null; });
    }

    /** Adds integer bin values to existing record bin values. */
    public void add(WritePolicy policy, Key key, Bin... bins) throws AerospikeException {
        db.traceSync(op("ADD", key), () -> { delegate.add(policy, key, bins); return null; });
    }

    /** Resets the record's time-to-live and increases generation. */
    public void touch(WritePolicy policy, Key key) throws AerospikeException {
        db.traceSync(op("TOUCH", key), () -> { delegate.touch(policy, key); return null; });
    }

    // ---- Delete ----

    /** Deletes a record. Returns {@code true} if the record existed. */
    public boolean delete(WritePolicy policy, Key key) throws AerospikeException {
        return db.traceSync(op("DELETE", key), () -> delegate.delete(policy, key));
    }

    /** Deletes multiple records in a batch. */
    public BatchResults delete(BatchPolicy policy, BatchDeletePolicy deletePolicy,
                               Key[] keys) throws AerospikeException {
        return db.traceSync(batchOp("DELETE", keys),
                () -> delegate.delete(policy, deletePolicy, keys));
    }

    // ---- Existence check ----

    /** Returns whether the record exists. */
    public boolean exists(Policy policy, Key key) throws AerospikeException {
        return db.traceSync(op("EXISTS", key), () -> delegate.exists(policy, key));
    }

    /** Returns whether multiple records exist. */
    public boolean[] exists(BatchPolicy policy, Key[] keys) throws AerospikeException {
        return db.traceSync(batchOp("EXISTS", keys), () -> delegate.exists(policy, keys));
    }

    // ---- Operate ----

    /** Performs multiple read/write operations on a single record atomically. */
    public Record operate(WritePolicy policy, Key key, Operation... ops) throws AerospikeException {
        return db.traceSync(op("OPERATE", key), () -> delegate.operate(policy, key, ops));
    }

    /** Performs read/write operations on multiple records in a batch. */
    public boolean operate(BatchPolicy policy, List<BatchRecord> records) throws AerospikeException {
        return db.traceSync("OPERATE (" + records.size() + " keys)",
                () -> delegate.operate(policy, records));
    }

    /** Performs write operations on multiple keys in a batch. */
    public BatchResults operate(BatchPolicy policy, BatchWritePolicy writePolicy,
                                Key[] keys, Operation... ops) throws AerospikeException {
        return db.traceSync(batchOp("OPERATE", keys),
                () -> delegate.operate(policy, writePolicy, keys, ops));
    }

    // ---- Query / Scan ----

    /** Executes a query and returns a {@link RecordSet}. */
    public RecordSet query(QueryPolicy policy, Statement stmt) throws AerospikeException {
        return db.traceSync("QUERY " + stmt.getSetName(), () -> delegate.query(policy, stmt));
    }

    /** Scans all records in a namespace/set. */
    public void scanAll(ScanPolicy policy, String namespace, String setName,
                        ScanCallback callback, String... binNames) throws AerospikeException {
        db.traceSync("SCAN " + namespace + "." + setName,
                () -> { delegate.scanAll(policy, namespace, setName, callback, binNames); return null; });
    }

    /** Scans all records on a specific node. */
    public void scanNode(ScanPolicy policy, String nodeName, String namespace, String setName,
                         ScanCallback callback, String... binNames) throws AerospikeException {
        db.traceSync("SCAN " + namespace + "." + setName,
                () -> { delegate.scanNode(policy, nodeName, namespace, setName, callback, binNames); return null; });
    }

    /** Scans records in specific partitions. */
    public void scanPartitions(ScanPolicy policy, PartitionFilter partitionFilter,
                               String namespace, String setName,
                               ScanCallback callback, String... binNames) throws AerospikeException {
        db.traceSync("SCAN " + namespace + "." + setName,
                () -> { delegate.scanPartitions(policy, partitionFilter, namespace, setName, callback, binNames); return null; });
    }

    // ---- UDF Execute ----

    /** Executes a UDF on a single record. */
    public Object execute(WritePolicy policy, Key key, String packageName, String functionName,
                          Value... args) throws AerospikeException {
        return db.traceSync(op("EXECUTE", key), () -> delegate.execute(policy, key, packageName, functionName, args));
    }

    /** Executes a batch UDF on multiple records. */
    public BatchResults execute(BatchPolicy policy, BatchUDFPolicy udfPolicy, Key[] keys,
                                String packageName, String functionName,
                                Value... args) throws AerospikeException {
        return db.traceSync(batchOp("EXECUTE", keys),
                () -> delegate.execute(policy, udfPolicy, keys, packageName, functionName, args));
    }

    // ---- Lifecycle (not traced) ----

    /** Returns whether the client is connected to a server node. */
    public boolean isConnected() {
        return delegate.isConnected();
    }

    /** Returns the list of active server nodes. */
    public Node[] getNodes() {
        return delegate.getNodes();
    }

    /** Closes this client and releases all resources. */
    public void close() {
        delegate.close();
    }

    // ---- Unwrap ----

    /**
     * Returns the underlying {@link IAerospikeClient} for operations not covered by this wrapper
     * (e.g., admin tasks, UDF registration, index management, async variants).
     *
     * @return the underlying client
     */
    public IAerospikeClient unwrap() {
        return delegate;
    }

    // ---- Helpers ----

    private static String op(String command, Key key) {
        if (key != null) {
            return command + " " + key.namespace + "." + key.setName;
        }
        return command;
    }

    private static String batchOp(String command, Key[] keys) {
        return command + " (" + (keys != null ? keys.length : 0) + " keys)";
    }
}
