package io.last9.tracing.otel.v3.agent;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice for {@code io.vertx.sqlclient.impl.SqlClientBase.query(String)}
 * and {@code io.vertx.sqlclient.impl.SqlClientBase.preparedQuery(String)}.
 *
 * <p>Intercepts the reactive SQL client API (Vert.x 3.8+) at the implementation level.
 * This covers MySQLPool, PgPool, and any other SqlClient implementation that extends
 * SqlClientBase.
 *
 * <p>The span is started when query()/preparedQuery() is called and ended when the
 * method returns. The actual async execution happens later, but the span captures
 * the SQL statement being issued.
 */
public class ReactiveSqlAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void onEnter(
            @Advice.This Object client,
            @Advice.Argument(0) String sql,
            @Advice.Local("otelSpan") Span span,
            @Advice.Local("otelScope") Scope scope) {

        span = ReactiveSqlHelper.startSpan(sql, client);
        if (span != null) {
            scope = span.makeCurrent();
        }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    static void onExit(
            @Advice.Thrown Throwable thrown,
            @Advice.Local("otelSpan") Span span,
            @Advice.Local("otelScope") Scope scope) {

        ReactiveSqlHelper.endSpan(span, scope, thrown);
    }
}
