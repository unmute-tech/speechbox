package io.reitmaier.speechbox

// Error Types
sealed class DomainError
sealed class IOError(open val message: String, open val throwable: Throwable?) : DomainError()
sealed class NetworkError(open val message: String, open val throwable: Throwable?) : DomainError()

data class ConnectionError(
  override val message: String,
  override val throwable: Throwable?
) : NetworkError(message, throwable)

data class InternalApiError(
  override val message: String,
  override val throwable: Throwable?
) : NetworkError(message, throwable)

data class MicError(
  override val message: String,
  override val throwable: Throwable?
) : IOError(message, throwable)

data class TimeoutError(
  override val message: String,
  override val throwable: Throwable?
) : IOError(message, throwable)

data class RecordingError(
  override val message: String,
  override val throwable: Throwable?
) : IOError(message, throwable)


data class EncodingError(
  override val message: String,
  override val throwable: Throwable?
) : IOError(message, throwable)

data class SpeakerError(
  override val message: String,
  override val throwable: Throwable?
) : IOError(message, throwable)
