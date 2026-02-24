package io.last9.tracing.otel.v3;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.reactivex.Maybe;
import io.vertx.reactivex.redis.client.RedisAPI;
import io.vertx.reactivex.redis.client.Response;

import java.util.List;

/**
 * A drop-in replacement for Vert.x 3 {@link RedisAPI} that automatically wraps the most
 * commonly used Redis commands with OpenTelemetry CLIENT spans.
 *
 * <p>Vert.x 3 has no {@code VertxTracer} SPI for Redis clients, so Redis operations produce
 * no spans by default. {@code TracedRedisClient} solves this by intercepting the ~40 most
 * common Redis commands and wrapping them with {@link DbTracing}.
 *
 * <p>Commands not explicitly overridden pass through to the delegate without tracing.
 * To trace additional commands manually, use {@link DbTracing} directly.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * RedisAPI redis = TracedRedisClient.wrap(RedisAPI.api(redisConnection), "0");
 *
 * // Every command automatically gets a CLIENT span:
 * redis.rxGet("session:abc")
 *     .subscribe(response -> { ... });
 * }</pre>
 *
 * @see DbTracing
 * @see RedisAPI
 */
public class TracedRedisClient extends RedisAPI {

    private final RedisAPI delegate;
    private final DbTracing db;

    private TracedRedisClient(RedisAPI delegate, DbTracing db) {
        super(delegate.getDelegate());
        this.delegate = delegate;
        this.db = db;
    }

    /**
     * Wraps an existing {@link RedisAPI} with tracing using {@link GlobalOpenTelemetry}.
     * The {@code db.name} attribute is omitted from spans.
     *
     * @param redisAPI the existing RedisAPI to wrap
     * @return a RedisAPI that auto-instruments common commands with CLIENT spans
     */
    public static TracedRedisClient wrap(RedisAPI redisAPI) {
        return wrap(redisAPI, null, GlobalOpenTelemetry.get());
    }

    /**
     * Wraps an existing {@link RedisAPI} with tracing using {@link GlobalOpenTelemetry}.
     *
     * @param redisAPI    the existing RedisAPI to wrap
     * @param dbNamespace the Redis database index or namespace (e.g., "0"); may be {@code null}
     * @return a RedisAPI that auto-instruments common commands with CLIENT spans
     */
    public static TracedRedisClient wrap(RedisAPI redisAPI, String dbNamespace) {
        return wrap(redisAPI, dbNamespace, GlobalOpenTelemetry.get());
    }

    /**
     * Wraps an existing {@link RedisAPI} with tracing using the supplied {@link OpenTelemetry}.
     * The {@code db.name} attribute is omitted from spans. Useful in tests.
     *
     * @param redisAPI      the existing RedisAPI to wrap
     * @param openTelemetry the OpenTelemetry instance to use
     * @return a RedisAPI that auto-instruments common commands with CLIENT spans
     */
    public static TracedRedisClient wrap(RedisAPI redisAPI, OpenTelemetry openTelemetry) {
        return wrap(redisAPI, null, openTelemetry);
    }

    /**
     * Wraps an existing {@link RedisAPI} with tracing using the supplied {@link OpenTelemetry}.
     * Useful in tests.
     *
     * @param redisAPI      the existing RedisAPI to wrap
     * @param dbNamespace   the Redis database index or namespace; may be {@code null} to omit
     * @param openTelemetry the OpenTelemetry instance to use
     * @return a RedisAPI that auto-instruments common commands with CLIENT spans
     */
    public static TracedRedisClient wrap(RedisAPI redisAPI, String dbNamespace,
                                         OpenTelemetry openTelemetry) {
        DbTracing db = DbTracing.create("redis", dbNamespace, openTelemetry);
        return new TracedRedisClient(redisAPI, db);
    }

    private static String firstKey(List<String> args) {
        return args != null && !args.isEmpty() ? args.get(0) : "";
    }

    // ---- Strings ----

    @Override
    public Maybe<Response> rxGet(String key) {
        return db.traceMaybe("GET " + key, () -> delegate.rxGet(key));
    }

    @Override
    public Maybe<Response> rxSet(List<String> args) {
        return db.traceMaybe("SET " + firstKey(args), () -> delegate.rxSet(args));
    }

    @Override
    public Maybe<Response> rxSetex(String key, String seconds, String value) {
        return db.traceMaybe("SETEX " + key, () -> delegate.rxSetex(key, seconds, value));
    }

    @Override
    public Maybe<Response> rxPsetex(String key, String millis, String value) {
        return db.traceMaybe("PSETEX " + key, () -> delegate.rxPsetex(key, millis, value));
    }

    @Override
    public Maybe<Response> rxSetnx(String key, String value) {
        return db.traceMaybe("SETNX " + key, () -> delegate.rxSetnx(key, value));
    }

    @Override
    public Maybe<Response> rxMget(List<String> args) {
        return db.traceMaybe("MGET (" + args.size() + " keys)", () -> delegate.rxMget(args));
    }

    @Override
    public Maybe<Response> rxMset(List<String> args) {
        return db.traceMaybe("MSET (" + (args.size() / 2) + " keys)", () -> delegate.rxMset(args));
    }

    @Override
    public Maybe<Response> rxIncr(String key) {
        return db.traceMaybe("INCR " + key, () -> delegate.rxIncr(key));
    }

    @Override
    public Maybe<Response> rxIncrby(String key, String increment) {
        return db.traceMaybe("INCRBY " + key, () -> delegate.rxIncrby(key, increment));
    }

    @Override
    public Maybe<Response> rxDecr(String key) {
        return db.traceMaybe("DECR " + key, () -> delegate.rxDecr(key));
    }

    @Override
    public Maybe<Response> rxDecrby(String key, String decrement) {
        return db.traceMaybe("DECRBY " + key, () -> delegate.rxDecrby(key, decrement));
    }

    @Override
    public Maybe<Response> rxAppend(String key, String value) {
        return db.traceMaybe("APPEND " + key, () -> delegate.rxAppend(key, value));
    }

    // ---- Hashes ----

    @Override
    public Maybe<Response> rxHget(String key, String field) {
        return db.traceMaybe("HGET " + key, () -> delegate.rxHget(key, field));
    }

    @Override
    public Maybe<Response> rxHset(List<String> args) {
        return db.traceMaybe("HSET " + firstKey(args), () -> delegate.rxHset(args));
    }

    @Override
    public Maybe<Response> rxHgetall(String key) {
        return db.traceMaybe("HGETALL " + key, () -> delegate.rxHgetall(key));
    }

    @Override
    public Maybe<Response> rxHdel(List<String> args) {
        return db.traceMaybe("HDEL " + firstKey(args), () -> delegate.rxHdel(args));
    }

    @Override
    public Maybe<Response> rxHmget(List<String> args) {
        return db.traceMaybe("HMGET " + firstKey(args), () -> delegate.rxHmget(args));
    }

    @Override
    public Maybe<Response> rxHmset(List<String> args) {
        return db.traceMaybe("HMSET " + firstKey(args), () -> delegate.rxHmset(args));
    }

    @Override
    public Maybe<Response> rxHexists(String key, String field) {
        return db.traceMaybe("HEXISTS " + key, () -> delegate.rxHexists(key, field));
    }

    @Override
    public Maybe<Response> rxHlen(String key) {
        return db.traceMaybe("HLEN " + key, () -> delegate.rxHlen(key));
    }

    // ---- Lists ----

    @Override
    public Maybe<Response> rxLpush(List<String> args) {
        return db.traceMaybe("LPUSH " + firstKey(args), () -> delegate.rxLpush(args));
    }

    @Override
    public Maybe<Response> rxRpush(List<String> args) {
        return db.traceMaybe("RPUSH " + firstKey(args), () -> delegate.rxRpush(args));
    }

    @Override
    public Maybe<Response> rxLpop(String key) {
        return db.traceMaybe("LPOP " + key, () -> delegate.rxLpop(key));
    }

    @Override
    public Maybe<Response> rxRpop(String key) {
        return db.traceMaybe("RPOP " + key, () -> delegate.rxRpop(key));
    }

    @Override
    public Maybe<Response> rxLrange(String key, String start, String stop) {
        return db.traceMaybe("LRANGE " + key, () -> delegate.rxLrange(key, start, stop));
    }

    @Override
    public Maybe<Response> rxLlen(String key) {
        return db.traceMaybe("LLEN " + key, () -> delegate.rxLlen(key));
    }

    // ---- Sets ----

    @Override
    public Maybe<Response> rxSadd(List<String> args) {
        return db.traceMaybe("SADD " + firstKey(args), () -> delegate.rxSadd(args));
    }

    @Override
    public Maybe<Response> rxSmembers(String key) {
        return db.traceMaybe("SMEMBERS " + key, () -> delegate.rxSmembers(key));
    }

    @Override
    public Maybe<Response> rxSrem(List<String> args) {
        return db.traceMaybe("SREM " + firstKey(args), () -> delegate.rxSrem(args));
    }

    @Override
    public Maybe<Response> rxScard(String key) {
        return db.traceMaybe("SCARD " + key, () -> delegate.rxScard(key));
    }

    // ---- Sorted Sets ----

    @Override
    public Maybe<Response> rxZadd(List<String> args) {
        return db.traceMaybe("ZADD " + firstKey(args), () -> delegate.rxZadd(args));
    }

    @Override
    public Maybe<Response> rxZrange(List<String> args) {
        return db.traceMaybe("ZRANGE " + firstKey(args), () -> delegate.rxZrange(args));
    }

    @Override
    public Maybe<Response> rxZrangebyscore(List<String> args) {
        return db.traceMaybe("ZRANGEBYSCORE " + firstKey(args),
                () -> delegate.rxZrangebyscore(args));
    }

    @Override
    public Maybe<Response> rxZrem(List<String> args) {
        return db.traceMaybe("ZREM " + firstKey(args), () -> delegate.rxZrem(args));
    }

    @Override
    public Maybe<Response> rxZcard(String key) {
        return db.traceMaybe("ZCARD " + key, () -> delegate.rxZcard(key));
    }

    // ---- Keys ----

    @Override
    public Maybe<Response> rxDel(List<String> args) {
        return db.traceMaybe("DEL " + firstKey(args), () -> delegate.rxDel(args));
    }

    @Override
    public Maybe<Response> rxExists(List<String> args) {
        return db.traceMaybe("EXISTS " + firstKey(args), () -> delegate.rxExists(args));
    }

    @Override
    public Maybe<Response> rxExpire(String key, String seconds) {
        return db.traceMaybe("EXPIRE " + key, () -> delegate.rxExpire(key, seconds));
    }

    @Override
    public Maybe<Response> rxTtl(String key) {
        return db.traceMaybe("TTL " + key, () -> delegate.rxTtl(key));
    }

    @Override
    public Maybe<Response> rxKeys(String pattern) {
        return db.traceMaybe("KEYS " + pattern, () -> delegate.rxKeys(pattern));
    }

    @Override
    public Maybe<Response> rxType(String key) {
        return db.traceMaybe("TYPE " + key, () -> delegate.rxType(key));
    }

    // ---- Pub/Sub ----

    @Override
    public Maybe<Response> rxPublish(String channel, String message) {
        return db.traceMaybe("PUBLISH " + channel, () -> delegate.rxPublish(channel, message));
    }

    // ---- Server ----

    @Override
    public Maybe<Response> rxPing(List<String> args) {
        return db.traceMaybe("PING", () -> delegate.rxPing(args));
    }

    @Override
    public Maybe<Response> rxSelect(String index) {
        return db.traceMaybe("SELECT " + index, () -> delegate.rxSelect(index));
    }

    // ---- Lifecycle (no tracing) ----

    @Override
    public void close() {
        delegate.close();
    }
}
