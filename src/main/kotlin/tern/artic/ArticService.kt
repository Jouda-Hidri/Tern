package tern.artic

import com.google.protobuf.Empty
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.stub.StreamObserver
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import tern.antarctic.Message
import tern.grpc.TernServiceGrpc
import tern.grpc.TernServiceOuterClass.SaveRequest
import tern.grpc.TernServiceOuterClass.SaveResponse
import tern.tracing.RequestId
import tern.tracing.RequestIdClientInterceptor
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths


@Service
class ArticService(
    @Value("\${tern.antarctic.target}") private val antarcticTarget: String,
    @Value("\${tern.tapi.url}") private val tapiUrl: String,
    requestIdClientInterceptor: RequestIdClientInterceptor,
) {
    private val logger = LoggerFactory.getLogger(ArticService::class.java)
    private var channel: ManagedChannel = ManagedChannelBuilder.forTarget(antarcticTarget)
        .usePlaintext()
        .intercept(requestIdClientInterceptor)
        .build()
    private var blockingStub = TernServiceGrpc.newBlockingStub(channel)
    private var stub = TernServiceGrpc.newStub(channel)
    var client: WebClient = WebClient.create(tapiUrl)

    fun find(): List<Message> {
        logger.info("Artic - Retrieving messages")
        val response = blockingStub.getMessage(Empty.getDefaultInstance())
        val list: MutableList<Message> = mutableListOf()
        response.forEach { getResponse ->
            list.add(Message(id = null, text = getResponse.text))
        }
        logger.info("Artic - Received ${list.size} message(s) from antarctic")
        return list
    }

    fun save(message: Message) {
        logger.info("Artic - Request message: $message")
        // The stub is async, so the callbacks below land on a gRPC executor thread that has
        // no MDC of its own - carry the id over by hand so their logs stay in the trace.
        val requestId = RequestId.current()
        stub.saveMessage(
            SaveRequest.newBuilder().setText(message.text).build(), object : StreamObserver<SaveResponse> {
                override fun onNext(response: SaveResponse?) = RequestId.withRequestId(requestId) {
                    // todo possible to use response as path param
                    logger.warn("Artic - $response")
                }

                override fun onError(throwable: Throwable?) = RequestId.withRequestId(requestId) {
                    logger.error("Artic - Error ${throwable?.message}")
                }

                override fun onCompleted() = RequestId.withRequestId(requestId) {
                    logger.info("Artic - Completed")
                    // tapi is an optional downstream: if it is not deployed (e.g. docker-compose),
                    // the message is already saved, so log and carry on instead of failing.
                    try {
                        val flux = client.get().retrieve().bodyToFlux(DataBuffer::class.java)

                        val outputStream = ByteArrayOutputStream()
                        val dataBufferOutputStream = DataBufferUtils.write(flux, outputStream)

                        dataBufferOutputStream.blockLast()
                        val byteBuffer = outputStream.toByteArray()

                        val inputStream = ByteArrayInputStream(byteBuffer)
                        val reader = BufferedReader(InputStreamReader(inputStream))

                        try {
                            while (true) {
                                val line = reader.readLine() ?: break
                                logger.info("Tapi - $line")
                            }
                        } finally {
                            reader.close()
                            inputStream.close()
                        }

                        logger.info("Tapi - Download completed.")
                    } catch (e: Exception) {
                        logger.warn("Tapi - Unreachable at $tapiUrl, skipping download: ${e.message}")
                    }
                }
            }
        )
    }

    fun status(): Int? {
        return client.get()
            .uri("/")
            .exchangeToMono { it.toEntity(Void::class.java) }
            .map { it.statusCode }
            .block()
            ?.value()
    }
}