package tern.tracing

import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ForwardingClientCall
import io.grpc.ForwardingClientCallListener
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.Status
import org.slf4j.LoggerFactory

/**
 * Carries the current request id across the wire to antarctic. The call is logged at DEBUG
 * only: antarctic logs its own entry, so at INFO the hop is already accounted for once.
 */
class RequestIdClientInterceptor : ClientInterceptor {
    private val log = LoggerFactory.getLogger(RequestIdClientInterceptor::class.java)

    override fun <ReqT, RespT> interceptCall(
        method: MethodDescriptor<ReqT, RespT>,
        callOptions: CallOptions,
        next: Channel,
    ): ClientCall<ReqT, RespT> {
        val requestId = RequestId.current()
        val startedAt = System.currentTimeMillis()

        return object : ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
            next.newCall(method, callOptions)
        ) {
            override fun start(responseListener: Listener<RespT>, headers: Metadata) {
                if (requestId != null) headers.put(RequestId.METADATA_KEY, requestId)
                log.debug("gRPC --> {} - calling antarctic", method.fullMethodName)

                val listener = object :
                    ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT>(responseListener) {
                    override fun onClose(status: Status, trailers: Metadata) {
                        RequestId.withRequestId(requestId) {
                            val took = System.currentTimeMillis() - startedAt
                            log.debug(
                                "gRPC <-- {} - {} in {} ms",
                                method.fullMethodName, status.code, took,
                            )
                        }
                        super.onClose(status, trailers)
                    }
                }
                super.start(listener, headers)
            }
        }
    }
}
