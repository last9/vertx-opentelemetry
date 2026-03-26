package io.last9.tracing.otel.v4.agent;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice for AWS SQS {@code receiveMessage()} — both SDK v1 and v2.
 *
 * <p>Intercepts the receive call to create a CONSUMER span covering the SQS
 * API round-trip and the number of messages returned. The span is made current
 * so that any downstream processing (Redis, HTTP, Aerospike calls) within the
 * same thread becomes a child of this consumer span.
 *
 * <p>Targets:
 * <ul>
 *   <li>SDK v1: {@code com.amazonaws.services.sqs.AmazonSQSClient.receiveMessage(ReceiveMessageRequest)}</li>
 *   <li>SDK v2: {@code software.amazon.awssdk.services.sqs.DefaultSqsClient.receiveMessage(ReceiveMessageRequest)}</li>
 * </ul>
 *
 * <p>All AWS types are accessed via reflection in {@link SqsReceiveHelper} to
 * avoid compile-time dependency on the AWS SDK.
 */
public class SqsReceiveAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void onEnter(
            @Advice.Argument(0) Object request,
            @Advice.Local("otelSpan") Span span,
            @Advice.Local("otelScope") Scope scope) {

        Object[] result = SqsReceiveHelper.startSpan(request);
        if (result != null) {
            span = (Span) result[0];
            scope = (Scope) result[1];
        }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    static void onExit(
            @Advice.Return Object response,
            @Advice.Thrown Throwable thrown,
            @Advice.Local("otelSpan") Span span,
            @Advice.Local("otelScope") Scope scope) {

        SqsReceiveHelper.endSpan(span, scope, response, thrown);
    }
}
