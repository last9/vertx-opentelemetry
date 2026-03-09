package io.last9.tracing.otel.v3.agent;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice for {@code io.vertx.ext.jdbc.impl.JDBCClientImpl} SQL methods.
 *
 * <p>Intercepts methods where the first argument is a SQL string:
 * {@code query(String, Handler)}, {@code queryWithParams(String, JsonArray, Handler)},
 * {@code update(String, Handler)}, {@code updateWithParams(String, JsonArray, Handler)},
 * {@code call(String, Handler)}, etc.
 *
 * <p>This covers all SQL operations through the legacy Vert.x JDBC client API
 * without requiring bootstrap classloader injection (unlike intercepting
 * {@code java.sql.Statement} directly).
 */
public class JdbcClientAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void onEnter(
            @Advice.This Object client,
            @Advice.Argument(0) String sql,
            @Advice.Local("otelSpan") Span span,
            @Advice.Local("otelScope") Scope scope) {

        span = JdbcClientHelper.startSpan(sql, client);
        if (span != null) {
            scope = span.makeCurrent();
        }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    static void onExit(
            @Advice.Thrown Throwable thrown,
            @Advice.Local("otelSpan") Span span,
            @Advice.Local("otelScope") Scope scope) {

        JdbcClientHelper.endSpan(span, scope, thrown);
    }
}
