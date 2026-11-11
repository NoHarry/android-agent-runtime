package cc.noharry.agent.appserver.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.net.URI

/** A URI as its string. */
object UriSerializer : KSerializer<URI> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("cc.noharry.agent.appserver.protocol.Uri", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: URI) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): URI = URI(decoder.decodeString())
}
