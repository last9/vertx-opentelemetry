package io.last9.tracing.otel.v3.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the shared {@link AgentGuard} ThreadLocal guard.
 */
class AgentGuardTest {

    @AfterEach
    void tearDown() {
        AgentGuard.IN_DB_TRACED_CALL.remove();
    }

    @Test
    void defaultValueIsFalse() {
        assertThat(AgentGuard.IN_DB_TRACED_CALL.get()).isFalse();
    }

    @Test
    void setToTrueAndBack() {
        AgentGuard.IN_DB_TRACED_CALL.set(true);
        assertThat(AgentGuard.IN_DB_TRACED_CALL.get()).isTrue();

        AgentGuard.IN_DB_TRACED_CALL.set(false);
        assertThat(AgentGuard.IN_DB_TRACED_CALL.get()).isFalse();
    }

    @Test
    void removeResetsToDefault() {
        AgentGuard.IN_DB_TRACED_CALL.set(true);
        AgentGuard.IN_DB_TRACED_CALL.remove();
        assertThat(AgentGuard.IN_DB_TRACED_CALL.get()).isFalse();
    }

    @Test
    void guardIsThreadLocal() throws InterruptedException {
        AgentGuard.IN_DB_TRACED_CALL.set(true);

        boolean[] otherThreadValue = {true}; // should be false on another thread
        Thread t = new Thread(() -> otherThreadValue[0] = AgentGuard.IN_DB_TRACED_CALL.get());
        t.start();
        t.join();

        assertThat(otherThreadValue[0]).isFalse();
        assertThat(AgentGuard.IN_DB_TRACED_CALL.get()).isTrue();
    }
}
