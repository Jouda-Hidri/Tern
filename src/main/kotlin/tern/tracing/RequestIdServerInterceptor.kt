package tern.tracing

import io.grpc.ForwardingServerCall
import io.grpc.ForwardingServerCallListener
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import org.slf4j.LoggerFactory

class RequestIdServerInterceptor : ServerInterceptor {
    private val log = LoggerFactory.getLogger(RequestIdServerInterceptor::class.java)

    override fun <ReqT, RespT> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> {
        val requestId = headers.get(RequestId.METADATA_KEY) ?: RequestId.newId()
        val method = call.methodDescriptor.fullMethodName
        val startedAt = System.currentTimeMillis()

        val tracedCall = object : ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
            override fun close(status: Status, trailers: Metadata) {
                RequestId.withRequestId(requestId) {
                    val took = System.currentTimeMillis() - startedAt
                    log.debug("gRPC <-- {} - {} in {} ms", method, status.code, took)
                }
                super.close(status, trailers)
            }
        }

        val delegate = RequestId.withRequestId(requestId) {
            log.info("gRPC --> {} - call received", method)
            next.startCall(tracedCall, headers)
        }

        // gRPC may run the callbacks on another thread, so the MDC is re-applied around each.
        return object : ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT>(delegate) {
            override fun onMessage(message: ReqT) =
                RequestId.withRequestId(requestId) { super.onMessage(message) }

            override fun onHalfClose() =
                RequestId.withRequestId(requestId) { super.onHalfClose() }

            override fun onCancel() =
                RequestId.withRequestId(requestId) { super.onCancel() }

            override fun onComplete() =
                RequestId.withRequestId(requestId) { super.onComplete() }

            override fun onReady() =
                RequestId.withRequestId(requestId) { super.onReady() }
        }
    }
}
