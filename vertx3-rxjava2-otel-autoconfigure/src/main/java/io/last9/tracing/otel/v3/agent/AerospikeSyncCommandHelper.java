package io.last9.tracing.otel.v3.agent;

import com.aerospike.client.Key;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * Helper for {@link AerospikeSyncCommandAdvice}. Extracts the operation name
 * and Key from the concrete SyncCommand subclass via reflection.
 *
 * <p>Command class → operation mapping:
 * <ul>
 *   <li>ReadCommand → GET</li>
 *   <li>WriteCommand → PUT</li>
 *   <li>DeleteCommand → DELETE</li>
 *   <li>ExistsCommand → EXISTS</li>
 *   <li>TouchCommand → TOUCH</li>
 *   <li>OperateCommand → OPERATE</li>
 *   <li>BatchSingle$* → BATCH</li>
 *   <li>Other → class simple name</li>
 * </ul>
 */
public final class AerospikeSyncCommandHelper {

    private static final Logger log = LoggerFactory.getLogger(AerospikeSyncCommandHelper.class);

    private static final Map<String, String> COMMAND_TO_OP = new HashMap<String, String>();
    static {
        COMMAND_TO_OP.put("ReadCommand", "GET");
        COMMAND_TO_OP.put("WriteCommand", "PUT");
        COMMAND_TO_OP.put("DeleteCommand", "DELETE");
        COMMAND_TO_OP.put("ExistsCommand", "EXISTS");
        COMMAND_TO_OP.put("TouchCommand", "TOUCH");
        COMMAND_TO_OP.put("OperateCommand", "OPERATE");
    }

    private AerospikeSyncCommandHelper() {}

    /**
     * Starts a CLIENT span for the given SyncCommand instance.
     * Extracts the Key via reflection from the command's {@code key} field.
     * Returns null if already inside a traced call or if extraction fails.
     */
    public static Span startSpan(Object command) {
        if (AgentGuard.IN_DB_TRACED_CALL.get()) {
            return null;
        }

        String className = command.getClass().getSimpleName();
        String operation = COMMAND_TO_OP.get(className);
        if (operation == null) {
            // Batch or unknown command — derive from class name
            if (className.contains("Batch")) {
                operation = "BATCH";
            } else {
                operation = className.replace("Command", "").toUpperCase();
            }
        }

        Key key = extractKey(command);
        return AerospikeClientHelper.startSpan(operation, key);
    }

    /**
     * Extracts the {@code key} field from the command object by walking up the class hierarchy.
     * Returns null if no key field is found (e.g., batch commands without a single key).
     */
    private static Key extractKey(Object command) {
        Class<?> clazz = command.getClass();
        while (clazz != null && clazz != Object.class) {
            try {
                Field keyField = clazz.getDeclaredField("key");
                keyField.setAccessible(true);
                Object value = keyField.get(command);
                if (value instanceof Key) {
                    return (Key) value;
                }
            } catch (NoSuchFieldException e) {
                // Try parent class
            } catch (Exception e) {
                log.debug("Failed to extract Aerospike key from {}: {}", clazz.getSimpleName(), e.getMessage());
                return null;
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }
}
