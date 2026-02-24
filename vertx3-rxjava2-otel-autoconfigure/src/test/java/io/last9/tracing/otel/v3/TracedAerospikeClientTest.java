package io.last9.tracing.otel.v3;

import com.aerospike.client.AerospikeException;
import com.aerospike.client.Bin;
import com.aerospike.client.IAerospikeClient;
import com.aerospike.client.Key;
import com.aerospike.client.Record;
import com.aerospike.client.policy.Policy;
import com.aerospike.client.policy.WritePolicy;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TracedAerospikeClientTest {

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

    private IAerospikeClient createTraced(IAerospikeClient stub) {
        return TracedAerospikeClient.wrap(stub, "test-ns", otel.getOpenTelemetry());
    }

    @Test
    void getCreatesClientSpan() {
        Record record = new Record(new HashMap<>(), 1, 0);
        IAerospikeClient stub = createStub(record, null);
        IAerospikeClient traced = createTraced(stub);

        Key key = new Key("test-ns", "users", "user:123");
        Record result = traced.get(null, key);

        assertThat(result).isNotNull();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);

        SpanData span = spans.get(0);
        assertThat(span.getName()).isEqualTo("aerospike GET test-ns.users");
        assertThat(span.getKind()).isEqualTo(SpanKind.CLIENT);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("db.system")))
                .isEqualTo("aerospike");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("db.name")))
                .isEqualTo("test-ns");
    }

    @Test
    void putCreatesSpan() {
        IAerospikeClient stub = createStub(null, null);
        IAerospikeClient traced = createTraced(stub);

        Key key = new Key("test-ns", "users", "user:456");
        traced.put(null, key, new Bin("name", "Alice"));

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).getName()).isEqualTo("aerospike PUT test-ns.users");
    }

    @Test
    void deleteCreatesSpan() {
        IAerospikeClient stub = createStub(null, null);
        IAerospikeClient traced = createTraced(stub);

        Key key = new Key("test-ns", "cache", "entry:789");
        traced.delete(null, key);

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).getName()).isEqualTo("aerospike DELETE test-ns.cache");
    }

    @Test
    void existsCreatesSpan() {
        IAerospikeClient stub = createStub(null, null);
        IAerospikeClient traced = createTraced(stub);

        Key key = new Key("test-ns", "users", "user:123");
        traced.exists(null, key);

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).getName()).isEqualTo("aerospike EXISTS test-ns.users");
    }

    @Test
    void errorRecordedOnSpan() {
        IAerospikeClient stub = createStub(null,
                new AerospikeException("Key not found"));
        IAerospikeClient traced = createTraced(stub);

        Key key = new Key("test-ns", "users", "missing");
        assertThatThrownBy(() -> traced.get(null, key))
                .isInstanceOf(AerospikeException.class);

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).getEvents()).anyMatch(e -> e.getName().equals("exception"));
    }

    @Test
    void wrapWithoutNamespaceOmitsDbNameAttribute() {
        IAerospikeClient stub = createStub(new Record(new HashMap<>(), 1, 0), null);
        IAerospikeClient traced = TracedAerospikeClient.wrap(stub, otel.getOpenTelemetry());

        Key key = new Key("test-ns", "users", "user:123");
        traced.get(null, key);

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);

        SpanData span = spans.get(0);
        assertThat(span.getKind()).isEqualTo(SpanKind.CLIENT);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("db.system"))).isEqualTo("aerospike");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("db.name"))).isNull();
    }

    @Test
    void lifecycleMethodsNotTraced() {
        IAerospikeClient stub = createStub(null, null);
        IAerospikeClient traced = createTraced(stub);

        traced.isConnected();
        traced.close();

        assertThat(spanExporter.getFinishedSpanItems()).isEmpty();
    }

    @Test
    void batchGetUsesKeyCount() {
        IAerospikeClient stub = createStub(null, null);
        IAerospikeClient traced = createTraced(stub);

        Key[] keys = new Key[]{
                new Key("test-ns", "users", "u1"),
                new Key("test-ns", "users", "u2")
        };
        traced.get(null, keys);

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).getName()).isEqualTo("aerospike GET (2 keys)");
    }

    /**
     * Creates a stub IAerospikeClient using a dynamic proxy.
     * Data-plane methods return the canned record or throw the error.
     */
    private static IAerospikeClient createStub(Record cannedRecord, AerospikeException error) {
        return (IAerospikeClient) Proxy.newProxyInstance(
                IAerospikeClient.class.getClassLoader(),
                new Class<?>[]{IAerospikeClient.class},
                (proxy, method, args) -> {
                    String name = method.getName();

                    // Lifecycle methods
                    if ("close".equals(name)) return null;
                    if ("isConnected".equals(name)) return true;
                    if ("getNodes".equals(name)) return new com.aerospike.client.cluster.Node[0];

                    // Data-plane — throw error if configured
                    if (error != null) throw error;

                    // Return type-appropriate defaults
                    Class<?> returnType = method.getReturnType();
                    if (returnType == Record.class) return cannedRecord;
                    if (returnType == Record[].class) {
                        int keyCount = 0;
                        if (args != null) {
                            for (Object arg : args) {
                                if (arg instanceof Key[]) {
                                    keyCount = ((Key[]) arg).length;
                                }
                            }
                        }
                        return new Record[keyCount];
                    }
                    if (returnType == boolean.class) return false;
                    if (returnType == boolean[].class) return new boolean[0];
                    if (returnType == void.class) return null;
                    return null;
                });
    }
}
