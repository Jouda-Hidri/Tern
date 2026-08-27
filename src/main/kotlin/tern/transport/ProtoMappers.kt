package tern.transport

import tern.domain.LanguageCode
import tern.domain.Message
import tern.domain.MessageId
import tern.domain.MessageText
import tern.grpc.TernServiceOuterClass.GetResponse
import tern.grpc.TernServiceOuterClass.SaveRequest
import tern.grpc.TernServiceOuterClass.SaveResponse

/**
 * The single place where the generated protobuf types meet the domain, so neither artic nor
 * antarctic has to know the shape of the wire format.
 *
 * proto3 has no null: an absent string arrives as "". Both directions treat empty as absent so
 * a message whose language could not be detected round trips as null rather than as "".
 */

fun Message.toGetResponse(): GetResponse =
    GetResponse.newBuilder()
        .setText(text.value)
        .also { builder ->
            id?.let { builder.id = it.toString() }
            language?.let { builder.language = it.value }
        }
        .build()

fun GetResponse.toDomain(): Message = Message(
    id = id.takeIf { it.isNotBlank() }?.let { MessageId.of(it) },
    text = MessageText(text),
    language = LanguageCode.parseOrNull(language.takeIf { it.isNotBlank() }),
)

fun Message.toSaveRequest(): SaveRequest =
    SaveRequest.newBuilder()
        .setText(text.value)
        .also { builder -> language?.let { builder.language = it.value } }
        .build()

fun SaveRequest.toDomain(): Message = Message(
    id = null,
    text = MessageText(text),
    language = LanguageCode.parseOrNull(language.takeIf { it.isNotBlank() }),
)

fun MessageId.toSaveResponse(): SaveResponse =
    SaveResponse.newBuilder().setId(toString()).build()
