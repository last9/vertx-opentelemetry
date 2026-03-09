package io.last9.tracing.otel.v3.agent;

import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice for reactive SQL pool constructors (MySQLPoolImpl, PgPoolImpl).
 *
 * <p>Captures connection metadata (host, port, database) at pool creation time
 * and stores it in {@link ReactiveSqlHelper#POOL_METADATA} for later lookup
 * during query execution. This follows a "capture at creation"
 * pattern — much more reliable than walking internal fields at query time.
 *
 * <p>The connect options argument is the 3rd constructor parameter (index 2)
 * for both MySQLPoolImpl and PgPoolImpl in Vert.x 3.9.x:
 * <ul>
 *   <li>{@code MySQLPoolImpl(ContextInternal, boolean, MySQLConnectOptions, PoolOptions)}</li>
 *   <li>{@code PgPoolImpl(ContextInternal, boolean, PgConnectOptions, PoolOptions)}</li>
 * </ul>
 */
public class ReactiveSqlPoolAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    static void onExit(
            @Advice.This Object pool,
            @Advice.Argument(2) Object connectOptions) {

        ReactiveSqlHelper.registerPool(pool, connectOptions);
    }
}
