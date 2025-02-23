package tern.artic

import com.google.protobuf.Empty
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.stub.StreamObserver
import org.slf4j.LoggerFactory
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import tern.grpc.TernServiceGrpc
import tern.grpc.TernServiceOuterClass.SaveRequest
import tern.grpc.TernServiceOuterClass.SaveResponse
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader


@Service
class ArticService() {
    private val logger = LoggerFactory.getLogger(ArticService::class.java)
    private var channel: ManagedChannel = ManagedChannelBuilder.forTarget("antarctic.default.svc.cluster.local:30000")
        .usePlaintext()
        .build()
    private var blockingStub = TernServiceGrpc.newBlockingStub(channel)
    private var stub = TernServiceGrpc.newStub(channel)
    var client: WebClient = WebClient.create("http://tapi.default.svc.cluster.local:5000")

    fun find(): List<String> {
        logger.info("Artic - Retrieving messages")
        val response = blockingStub.getMessage(Empty.getDefaultInstance())
        val list: MutableList<String> = mutableListOf()
        response.forEach { getResponse ->
            list.add(getResponse.text)
        }
        return list
    }

    fun save(message: String) {
        logger.info("Artic - Request message: $message")
        stub.saveMessage(
            SaveRequest.newBuilder().setText(message).build(), object : StreamObserver<SaveResponse> {
                override fun onNext(response: SaveResponse?) {
                    // todo possible to use response as path param
                    logger.warn("Artic - $response")
                }

                override fun onError(throwable: Throwable?) {
                    logger.error("Artic - Error ${throwable?.message}")
                }

                override fun onCompleted() {
                    logger.info("Artic - Completed")
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