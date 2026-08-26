package tern.transport

import tern.domain.Message
import tern.domain.MessageId
import tern.domain.MessageText
import tern.grpc.TernServiceOuterClass.GetResponse
import tern.grpc.TernServiceOuterClass.SaveRequest
import tern.grpc.TernServiceOuterClass.SaveResponse

/**
 * The single place where the generated protobuf types meet the domain, so neither artic nor
 * antarctic has to know the shape of the wire format.
 */

fun Message.toGetResponse(): GetResponse =
    GetResponse.newBuilder()
        .setText(text.value)
        .also { builder -> id?.let { builder.id = it.toString() } }
        .build()

fun GetResponse.toDomain(): Message =
    Message(id.takeIf { it.isNotBlank() }?.let { MessageId.of(it) }, MessageText(text))

fun Message.toSaveRequest(): SaveRequest =
    SaveRequest.newBuilder().setText(text.value).build()

fun SaveRequest.toDomain(): Message = Message(MessageText(text))

fun MessageId.toSaveResponse(): SaveResponse =
    SaveResponse.newBuilder().setId(toString()).build()
