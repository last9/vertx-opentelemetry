package io.last9.tracing.otel.v3;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TracedRedisClientTest {

    private TestOtelSetup otel;
    private InMemorySpanExporter spanExporter;

    @BeforeEach
    void setUp() {
        otel = new TestOtelSetup();
        spanExporter = otel.getSpanExporter();
    }

    @AfterEach
    void tearDown() {
        otel.shutdown();
    }

    private io.vertx.reactivex.redis.client.RedisAPI createTraced(RuntimeException error) {
        RedisAPI stub = createStubRedisAPI(error);
        return TracedRedisClient.wrap(
                io.vertx.reactivex.redis.client.RedisAPI.newInstance(stub),
                "0", otel.getOpenTelemetry());
    }

    @Test
    void rxGetCreatesClientSpan() {
        io.vertx.reactivex.redis.client.RedisAPI traced = createTraced(null);

        traced.rxGet("session:abc").blockingGet();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);

        SpanData span = spans.get(0);
        assertThat(span.getName()).isEqualTo("redis GET session:abc");
        assertThat(span.getKind()).isEqualTo(SpanKind.CLIENT);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("db.system")))
                .isEqualTo("redis");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("db.name")))
                .isEqualTo("0");
    }

    @Test
    void rxSetCreatesSpan() {
        io.vertx.reactivex.redis.client.RedisAPI traced = createTraced(null);

        traced.rxSet(Arrays.asList("mykey", "myvalue")).blockingGet();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).getName()).isEqualTo("redis SET mykey");
    }

    @Test
    void rxHgetallCreatesSpan() {
        RedisAPI stub = createStubRedisAPI(null);
        io.vertx.reactivex.redis.client.RedisAPI traced = TracedRedisClient.wrap(
                io.vertx.reactivex.redis.client.RedisAPI.newInstance(stub),
                "1", otel.getOpenTelemetry());

        traced.rxHgetall("user:42").blockingGet();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).getName()).isEqualTo("redis HGETALL user:42");
        assertThat(spans.get(0).getAttributes().get(AttributeKey.stringKey("db.name")))
                .isEqualTo("1");
    }

    @Test
    void rxDelCreatesSpan() {
        io.vertx.reactivex.redis.client.RedisAPI traced = createTraced(null);

        traced.rxDel(Arrays.asList("key1", "key2")).blockingGet();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).getName()).isEqualTo("redis DEL key1");
    }

    @Test
    void rxLpushCreatesSpan() {
        io.vertx.reactivex.redis.client.RedisAPI traced = createTraced(null);

        traced.rxLpush(Arrays.asList("mylist", "val1", "val2")).blockingGet();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).getName()).isEqualTo("redis LPUSH mylist");
    }

    @Test
    void wrapWithoutNamespaceOmitsDbNameAttribute() {
        RedisAPI stub = createStubRedisAPI(null);
        io.vertx.reactivex.redis.client.RedisAPI traced = TracedRedisClient.wrap(
                io.vertx.reactivex.redis.client.RedisAPI.newInstance(stub),
                otel.getOpenTelemetry());

        traced.rxGet("session:abc").blockingGet();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);

        SpanData span = spans.get(0);
        assertThat(span.getKind()).isEqualTo(SpanKind.CLIENT);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("db.system"))).isEqualTo("redis");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("db.name"))).isNull();
    }

    @Test
    void errorRecordedOnSpan() {
        io.vertx.reactivex.redis.client.RedisAPI traced =
                createTraced(new RuntimeException("WRONGTYPE"));

        assertThatThrownBy(() -> traced.rxGet("bad-key").blockingGet())
                .isInstanceOf(RuntimeException.class);

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).getEvents()).anyMatch(e -> e.getName().equals("exception"));
    }

    /**
     * Creates a RedisAPI stub using a dynamic proxy.
     * All methods that take a Handler callback invoke it with either success(null)
     * or failure(error). The close() method is a no-op.
     */
    @SuppressWarnings("unchecked")
    private static RedisAPI createStubRedisAPI(RuntimeException error) {
        return (RedisAPI) Proxy.newProxyInstance(
                RedisAPI.class.getClassLoader(),
                new Class<?>[]{RedisAPI.class},
                (proxy, method, args) -> {
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    // Find the Handler<AsyncResult<Response>> callback parameter
                    if (args != null) {
                        for (Object arg : args) {
                            if (arg instanceof Handler) {
                                Handler<AsyncResult<Response>> handler =
                                        (Handler<AsyncResult<Response>>) arg;
                                if (error != null) {
                                    handler.handle(Future.failedFuture(error));
                                } else {
                                    handler.handle(Future.succeededFuture(null));
                                }
                                return proxy; // RedisAPI methods return this
                            }
                        }
                    }
                    return null;
                });
    }
}
