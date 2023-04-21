package io.reitmaier.speechbox

import kotlinx.datetime.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import io.reitmaier.speechbox.audio.SilenceDetectingAudioRecorder
import java.io.File
import java.util.*

@Serializable(with = BoxIdSerializer::class)
@JvmInline
value class BoxId(val value: Int) {
  companion object {
    val TEST = BoxId(1)
    val DEV = BoxId(1)
  }
}

@Serializable(with = StoryIdSerializer::class)
@JvmInline
value class StoryId(val value: Int) {
  companion object {
    val TEST = StoryId(1)
  }
}
@Serializable(with = ParticipationTokenSerializer::class)
@JvmInline
value class ParticipationToken(val value: String) {
  companion object {
    val NO_TOKEN_AVAILABLE = ParticipationToken("NO_TOKEN_AVAILABLE")
    val TEST = ParticipationToken("TOKEN")
  }
}

@Serializable(with = SessionIdSerializer::class)
@JvmInline
value class SessionId(val value: UUID) {
  companion object {
    val TEST = SessionId(UUID.randomUUID())
    fun new() = SessionId(UUID.randomUUID())
  }
}

@JvmInline
value class RawAudioFile(val value: File) {
  fun mp3File() = File("${this.value.parent}/${this.value.nameWithoutExtension}${SilenceDetectingAudioRecorder.MP3_AUDIO_FILE_EXTENSION}")
  override fun toString() : String {
    return value.name
  }
}

@JvmInline
value class Mp3AudioFile(val value: File) {
  override fun toString() : String {
    return value.name
  }
}

@Serializable
data class MobileInfo(
  val number: MobileNumber,
  val network: String,
)

@Serializable(with = MobileNumberSerializer::class)
@JvmInline
value class MobileNumber(val value: String) {
  companion object {
    val TEST = MobileNumber("0111111111")
  }
}

@Serializable(with = RecordingStopReasonSerializer::class)
@JvmInline
value class RecordingStopReason(val value: String) {
  companion object {
    val TEST = RecordingStopReason("MainButton (Test)")
  }
}

object RecordingStopReasonSerializer : KSerializer<RecordingStopReason> {
  override val descriptor: SerialDescriptor
    get() = PrimitiveSerialDescriptor("data.RecordingStopReason", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: RecordingStopReason) {
    encoder.encodeString(value.value)
  }

  override fun deserialize(decoder: Decoder): RecordingStopReason {
    return RecordingStopReason(decoder.decodeString())
  }
}
object MobileNumberSerializer : KSerializer<MobileNumber> {
  override val descriptor: SerialDescriptor
    get() = PrimitiveSerialDescriptor("data.MobileNumber", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: MobileNumber) {
    encoder.encodeString(value.value)
  }

  override fun deserialize(decoder: Decoder): MobileNumber {
    return MobileNumber(decoder.decodeString())
  }
}

object BoxIdSerializer : KSerializer<BoxId> {
  override val descriptor: SerialDescriptor
    get() = PrimitiveSerialDescriptor("data.BoxId", PrimitiveKind.INT)

  override fun serialize(encoder: Encoder, value: BoxId) {
    encoder.encodeInt(value.value)
  }

  override fun deserialize(decoder: Decoder): BoxId {
    return BoxId(decoder.decodeInt())
  }
}

object StoryIdSerializer : KSerializer<StoryId> {
  override val descriptor: SerialDescriptor
    get() = PrimitiveSerialDescriptor("data.StoryId", PrimitiveKind.INT)

  override fun serialize(encoder: Encoder, value: StoryId) {
    encoder.encodeInt(value.value)
  }

  override fun deserialize(decoder: Decoder): StoryId {
    return StoryId(decoder.decodeInt())
  }
}


object SessionIdSerializer : KSerializer<SessionId> {
  override val descriptor: SerialDescriptor
    get() = PrimitiveSerialDescriptor("data.SessionId", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: SessionId) {
    encoder.encodeString(value.value.toString())
  }

  override fun deserialize(decoder: Decoder): SessionId {
    return SessionId(UUID.fromString(decoder.decodeString()))
  }
}
object ParticipationTokenSerializer : KSerializer<ParticipationToken> {
  override val descriptor: SerialDescriptor
    get() = PrimitiveSerialDescriptor("data.ParticipationToken", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: ParticipationToken) {
    encoder.encodeString(value.value)
  }

  override fun deserialize(decoder: Decoder): ParticipationToken {
    return ParticipationToken(decoder.decodeString())
  }
}

object InstantEpochSerializer : KSerializer<Instant> {
  override val descriptor: SerialDescriptor
    get() = PrimitiveSerialDescriptor("Instant", PrimitiveKind.LONG)

  override fun serialize(encoder: Encoder, value: Instant) =
    encoder.encodeLong(value.toEpochMilliseconds())

  override fun deserialize(decoder: Decoder): Instant =
    Instant.fromEpochMilliseconds(decoder.decodeLong())
}
