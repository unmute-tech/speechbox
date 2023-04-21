package io.reitmaier.speechbox

import com.github.michaelbull.result.Result
import kotlinx.coroutines.Deferred
import java.io.File
import java.io.IOError

typealias IOResult<T> = Result<T, IOError>
typealias ApiResult<T> = Result<T, NetworkError>
sealed interface StateSession {
  val sessionId: SessionId
}

sealed class State : StateSession {
  data class Init(
    override val sessionId: SessionId = SessionId.new()
  ) : State()
  data class Idle(
    override val sessionId: SessionId = SessionId.new()
  ) : State()

  data class WelcomePrompt(
    override val sessionId: SessionId,
  ) : State()

  data class Recording(
    override val sessionId: SessionId,
  ) : State()

//  data class Recording(
//    override val sessionId: SessionId,
//    val audioFlow: Flow<AudioEvent>,
//  ) : State(sessionId)

  data class ConfirmationPrompt(
    override val sessionId: SessionId,
    val rawAudio: RawAudioFile,
    val mp3Task: Deferred<Result<Mp3AudioFile, EncodingError>>,
    val replayCount: Int,
  ) : State()


  data class ReplayAudio(
    override val sessionId: SessionId,
    val rawAudio: RawAudioFile,
    val mp3Task: Deferred<Result<Mp3AudioFile, EncodingError>>,
    val replayCount: Int,
  ) : State()



  data class Upload(
    override val sessionId: SessionId,
    val rawAudio: RawAudioFile,
    val mp3Task: Deferred<Result<Mp3AudioFile, EncodingError>>,
  ) : State()


  data class MobileEntryPrompt(
    override val sessionId: SessionId,
    val fileUploaded: File,
    val storyId: StoryId,
    val uploadTask: Deferred<ApiResult<Unit>>,
  ) : State()

//  data class QuestionnairePromptNoShare(
//    override val sessionId: SessionId,
//  ) : State()

//  data class TokenPrompt(
//    override val sessionId: SessionId,
//    val token: ParticipationToken,
//  ) : State()

//  data class NoTokenPrompt(
//    override val sessionId: SessionId,
//  ) : State()

  data class ThankYouPrompt(
    override val sessionId: SessionId,
  ) : State()

  data class NetworkError(
    override val sessionId: SessionId,
    val message: String,
    val throwable: Throwable?
  ) : State()

  data class AudioError(
    override val sessionId: SessionId,
    val message: String,
    val throwable: Throwable?
  ) : State()

  data class HardwareError(
    override val sessionId: SessionId,
    val message: String,
    val throwable: Throwable
  ) : State()

}


