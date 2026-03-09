package io.last9.tracing.otel.v3.agent;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice for raw JDBC {@code java.sql.Statement} implementations.
 *
 * <p>Intercepts {@code execute(String)}, {@code executeQuery(String)}, and
 * {@code executeUpdate(String)} on any Statement implementation (MySQL, PostgreSQL,
 * H2, etc.) to create CLIENT spans with SQL operation details.
 *
 * <p>This covers JDBC usage that goes directly through the JDBC driver, bypassing
 * the Vert.x {@code JDBCClientImpl} wrapper. Uses {@link AgentGuard} to prevent
 * double-instrumentation when also using Vert.x JDBC or TracedSQLClient.
 */
public class JdbcStatementAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void onEnter(
            @Advice.Argument(0) String sql,
            @Advice.This Object statement,
            @Advice.Local("otelSpan") Span span,
            @Advice.Local("otelScope") Scope scope) {

        span = JdbcStatementHelper.startSpan(sql, statement);
        if (span != null) {
            scope = span.makeCurrent();
        }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    static void onExit(
            @Advice.Thrown Throwable thrown,
            @Advice.Local("otelSpan") Span span,
            @Advice.Local("otelScope") Scope scope) {

        JdbcStatementHelper.endSpan(span, scope, thrown);
    }
}
