package app.pocketpilot.capability.api.screen

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.IntArraySerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Writes [Bounds] as `[left, top, right, bottom]`, which is a quarter of the size of an object. */
internal object BoundsSerializer : KSerializer<Bounds> {
    private val delegate = IntArraySerializer()

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor = SerialDescriptor("app.pocketpilot.Bounds", delegate.descriptor)

    override fun serialize(
        encoder: Encoder,
        value: Bounds,
    ) = encoder.encodeSerializableValue(delegate, intArrayOf(value.left, value.top, value.right, value.bottom))

    override fun deserialize(decoder: Decoder): Bounds {
        val values = decoder.decodeSerializableValue(delegate)
        require(values.size == 4) { "Bounds must have four values" }
        return Bounds(values[0], values[1], values[2], values[3])
    }
}
