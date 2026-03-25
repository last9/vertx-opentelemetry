package io.last9.tracing.otel.v4.agent;

import com.aerospike.client.Key;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    /** Cache resolved Field per command class — avoids repeated reflection on every call. */
    private static final ConcurrentHashMap<Class<?>, Field> KEY_FIELD_CACHE =
            new ConcurrentHashMap<Class<?>, Field>();

    /** Sentinel value for classes that have no 'key' field. */
    private static final Field NO_KEY_FIELD;
    static {
        try {
            NO_KEY_FIELD = AerospikeSyncCommandHelper.class.getDeclaredField("KEY_FIELD_CACHE");
        } catch (NoSuchFieldException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private AerospikeSyncCommandHelper() {}

    /**
     * Starts a CLIENT span for the given SyncCommand instance.
     * Extracts the Key via reflection from the command's {@code key} field.
     * Returns null if already inside a traced call or if extraction fails.
     */
    public static Span startSpan(Object command) {
        // Guard check delegated to AerospikeClientHelper.startSpan()
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
     * Extracts the {@code key} field from the command object. Uses a per-class cache
     * to avoid repeated reflection on every Aerospike call (~6 concrete command classes).
     */
    private static Key extractKey(Object command) {
        Class<?> commandClass = command.getClass();
        Field cached = KEY_FIELD_CACHE.get(commandClass);

        if (cached == NO_KEY_FIELD) {
            return null;
        }
        if (cached != null) {
            try {
                return (Key) cached.get(command);
            } catch (Exception e) {
                return null;
            }
        }

        // First time seeing this class — walk hierarchy once, cache result
        Class<?> clazz = commandClass;
        while (clazz != null && clazz != Object.class) {
            try {
                Field keyField = clazz.getDeclaredField("key");
                keyField.setAccessible(true);
                KEY_FIELD_CACHE.put(commandClass, keyField);
                Object value = keyField.get(command);
                return value instanceof Key ? (Key) value : null;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (Exception e) {
                KEY_FIELD_CACHE.put(commandClass, NO_KEY_FIELD);
                return null;
            }
        }
        KEY_FIELD_CACHE.put(commandClass, NO_KEY_FIELD);
        return null;
    }
}
