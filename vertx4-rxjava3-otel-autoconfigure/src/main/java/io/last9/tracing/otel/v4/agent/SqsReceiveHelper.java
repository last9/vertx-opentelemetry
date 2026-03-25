package io.last9.tracing.otel.v4.agent;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.semconv.ExceptionAttributes;
import io.opentelemetry.semconv.SemanticAttributes;

import java.util.Collection;

/**
 * Helper methods called by {@link SqsReceiveAdvice} to create CONSUMER spans
 * for AWS SQS {@code receiveMessage()} calls.
 *
 * <p>All AWS SDK types are accessed via reflection so that this library has no
 * compile-time dependency on the AWS SDK. Works with both SDK v1 and v2.
 *
 * <h2>Span semantics</h2>
 * <ul>
 *   <li>Kind: CONSUMER</li>
 *   <li>Name: {@code {queue-name} receive}</li>
 *   <li>Attributes: messaging.system=aws_sqs, messaging.destination.name, messaging.operation,
 *       messaging.batch.message_count, peer.service=sqs</li>
 * </ul>
 *
 * <p>The span is made current during the receive call, so downstream processing
 * (Redis, HTTP, Aerospike) within the same thread becomes a child span.
 */
public final class SqsReceiveHelper {

    private static final String TRACER_NAME = "io.last9.tracing.otel.v4";

    private SqsReceiveHelper() {}

    /**
     * Starts a CONSUMER span before the receiveMessage call.
     *
     * @param requestObj the ReceiveMessageRequest (SDK v1 or v2)
     * @return Object[]{Span, Scope} or null if span creation failed
     */
    public static Object[] startSpan(Object requestObj) {
        try {
            String queueUrl = extractQueueUrl(requestObj);
            String queueName = extractQueueName(queueUrl);

            OpenTelemetry otel = GlobalOpenTelemetry.get();
            Tracer tracer = otel.getTracer(TRACER_NAME);

            String serverAddr = extractServerAddress(queueUrl);

            Span span = tracer.spanBuilder(queueName + " receive")
                    .setParent(Context.root())
                    .setSpanKind(SpanKind.CONSUMER)
                    .setAttribute(SemanticAttributes.MESSAGING_SYSTEM, "aws_sqs")
                    .setAttribute(SemanticAttributes.MESSAGING_DESTINATION_NAME, queueName)
                    .setAttribute(SemanticAttributes.MESSAGING_OPERATION,
                            SemanticAttributes.MessagingOperationValues.RECEIVE)
                    .setAttribute("messaging.aws.sqs.queue_url", queueUrl)
                    .setAttribute("peer.service", "sqs")
                    .setAttribute(SemanticAttributes.SERVER_ADDRESS, serverAddr)
                    .setAttribute(SemanticAttributes.NET_PEER_NAME, serverAddr)
                    .startSpan();

            Scope scope = span.makeCurrent();
            return new Object[]{span, scope};
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Ends the CONSUMER span after the receiveMessage call returns.
     * Records the number of messages received and any errors.
     */
    public static void endSpan(Span span, Scope scope, Object responseObj, Throwable thrown) {
        if (span == null) return;

        try {
            if (thrown != null) {
                span.recordException(thrown,
                        Attributes.of(ExceptionAttributes.EXCEPTION_ESCAPED, true));
                span.setStatus(StatusCode.ERROR, thrown.getMessage());
            } else if (responseObj != null) {
                int messageCount = extractMessageCount(responseObj);
                span.setAttribute(SemanticAttributes.MESSAGING_BATCH_MESSAGE_COUNT,
                        (long) messageCount);
            }
        } finally {
            if (scope != null) {
                scope.close();
            }
            span.end();
        }
    }

    /**
     * Extracts queue URL from ReceiveMessageRequest via reflection.
     * Supports both SDK v1 (getQueueUrl()) and v2 (queueUrl()).
     */
    private static String extractQueueUrl(Object request) {
        // SDK v1: com.amazonaws.services.sqs.model.ReceiveMessageRequest.getQueueUrl()
        try {
            return (String) request.getClass().getMethod("getQueueUrl").invoke(request);
        } catch (Exception ignored) {}

        // SDK v2: software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest.queueUrl()
        try {
            return (String) request.getClass().getMethod("queueUrl").invoke(request);
        } catch (Exception ignored) {}

        return "unknown";
    }

    /**
     * Extracts the queue name from a queue URL.
     * SQS URL format: https://sqs.{region}.amazonaws.com/{account}/{queue-name}
     */
    private static String extractQueueName(String queueUrl) {
        if (queueUrl == null || queueUrl.isEmpty()) return "unknown";
        // Handle both URL format and plain queue name
        int lastSlash = queueUrl.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < queueUrl.length() - 1) {
            return queueUrl.substring(lastSlash + 1);
        }
        return queueUrl;
    }

    /**
     * Extracts the server address (hostname) from a queue URL.
     * e.g. https://sqs.us-east-1.amazonaws.com/123456789/my-queue → sqs.us-east-1.amazonaws.com
     */
    private static String extractServerAddress(String queueUrl) {
        if (queueUrl == null || queueUrl.isEmpty()) return "unknown";
        try {
            java.net.URI uri = java.net.URI.create(queueUrl);
            String host = uri.getHost();
            return host != null ? host : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Extracts message count from the response via reflection.
     * Supports both SDK v1 (getMessages()) and v2 (messages()).
     */
    private static int extractMessageCount(Object response) {
        // SDK v1: ReceiveMessageResult.getMessages() returns List<Message>
        try {
            Object messages = response.getClass().getMethod("getMessages").invoke(response);
            if (messages instanceof Collection) {
                return ((Collection<?>) messages).size();
            }
        } catch (Exception ignored) {}

        // SDK v2: ReceiveMessageResponse.messages() returns List<Message>
        try {
            Object messages = response.getClass().getMethod("messages").invoke(response);
            if (messages instanceof Collection) {
                return ((Collection<?>) messages).size();
            }
        } catch (Exception ignored) {}

        return 0;
    }
}
