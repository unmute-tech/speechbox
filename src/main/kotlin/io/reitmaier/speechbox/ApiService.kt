package io.reitmaier.speechbox

import com.github.michaelbull.logging.InlineLogger
import com.github.michaelbull.result.*
import com.github.michaelbull.retry.ContinueRetrying
import com.github.michaelbull.retry.StopRetrying
import com.github.michaelbull.retry.policy.RetryPolicy
import com.github.michaelbull.retry.policy.limitAttempts
import com.github.michaelbull.retry.policy.plus
import com.github.michaelbull.retry.retryResult
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import io.reitmaier.speechbox.io.Confirmation
import java.io.File
import kotlin.time.Duration

@Suppress("RemoveExplicitTypeArguments")
class ApiService(
  private val apiUrl: String,
  private val apiPassword: String? = null,
  boxId: BoxId = BoxId(1),
) {
  private val log = InlineLogger()
  private val baseURL = "${apiUrl.trimEnd('/')}/box/${boxId.value}"
  private val retryTimeouts: RetryPolicy<NetworkError> = {
    if (reason is InternalApiError) StopRetrying else ContinueRetrying
  }
  private val client = if(apiPassword != null) {
    HttpClient(CIO) {
      install(ContentNegotiation) {
        json()
      }
      install(Auth) {
        basic {
          credentials {
            BasicAuthCredentials(username = "speechbox", password = apiPassword)
          }
          realm = "Access to the '/' path"

          // Don't wait for 401 response before sending credentials
          sendWithoutRequest { request ->
            request.url.host == baseURL.substringAfter("https://").substringBefore("/")
          }
        }
      }
      expectSuccess = false
    }
  } else {
    HttpClient(CIO) {
      install(ContentNegotiation) {
        json()
      }
      expectSuccess = false
    }
  }

  suspend fun requestStoryId(sessionId: SessionId) : ApiResult<StoryId> {
    val url = "$baseURL/sessions/${sessionId.value}/story"
    return retryResult(limitAttempts(3)) {
      runCatching {
        log.debug { "Requesting StoryId: POST $url" }
        val response = client.post(url)
        if(response.status.isSuccess()) {
          response.body<StoryId>()
        } else {
          log.error { response.status }
          throw Throwable(response.status.description)
        }
      }.mapError { InternalApiError("Error requesting $url",it) }
    }
  }
  suspend fun uploadStoryAudio(storyId: StoryId, file: File) : ApiResult<Unit> {
    val url = "$apiUrl/story/${storyId.value}/file"
    log.debug { "Uploading ${file.name} to: POST $url" }
    return retryResult(retryTimeouts + limitAttempts(3)) {
      runCatching {
        client.post(url) {
          setBody(
            MultiPartFormDataContent(
              formData {
                append("extension", file.extension)
                append("file", file.readBytes(), Headers.build {
                  append(HttpHeaders.ContentDisposition, "filename=${file.name}")
                })
              },
              "Question Audio",
              ContentType.MultiPart.FormData.withParameter("boundary", "Question Audio")
            )
          )
        }
      }.mapError { ConnectionError("Could not upload audio file (${file.name}) with $storyId",it) }
        .andThen {
          if(it.status.isSuccess())
            Ok(Unit)
          else
            Err(InternalApiError("File (${file.name}) upload Unsuccessful: ${it.status}", null))
        }
    }
  }

  // TODO Handle 204 No Content "error" code when no more tokens available
  suspend fun submitMobile(sessionId: SessionId, mobileInfo: MobileInfo) : ApiResult<ParticipationToken> {
    val url = "$baseURL/sessions/${sessionId.value}/mobile"
    return retryResult(retryTimeouts + limitAttempts(3)) {
      runCatching {
        log.debug { "Requesting: $url" }
        client.post(url) {
          contentType(ContentType.Application.Json)
          setBody(mobileInfo)
        }
      }.mapError {
        ConnectionError("Could not submit mobile info: POST $url", it)
          .also { log.error { it } }
      }
        .andThen {response ->
          runCatching {
            if(response.status.isSuccess()) {
              response.body<ParticipationToken>()
            } else if(response.status.value ==  HttpStatusCode.NotFound.value ) {
              ParticipationToken.NO_TOKEN_AVAILABLE
            }
            else {
              log.error { response.status }
              throw Throwable(response.status.toString())
            }
          }.mapError {
            InternalApiError("Could not receive token: POST $url", it)
              .also { log.error { it } }
          }
        }
    }
  }

  suspend fun requestToken(sessionId: SessionId, mobileNumber: MobileNumber) : ApiResult<ParticipationToken> {
    val url = "$baseURL/sessions/${sessionId.value}/token"
    return retryResult(retryTimeouts + limitAttempts(3)) {
      runCatching {
        log.debug { "Requesting: $url" }
        client.post(url) {
          contentType(ContentType.Application.Json)
          setBody(mobileNumber)
        }
      }.mapError {
        ConnectionError("Could not request token from: POST $url", it)
          .also { log.error { it } }
      }
        .andThen {response ->
          runCatching {
            if(response.status.isSuccess()) {
              response.body<ParticipationToken>()
            } else if(response.status.value ==  HttpStatusCode.NotFound.value ) {
              ParticipationToken.NO_TOKEN_AVAILABLE
            }
            else {
              log.error { response.status }
              throw Throwable(response.status.toString())
            }
          }.mapError {
            InternalApiError("Could not receive token: POST $url", it)
              .also { log.error { it } }
          }
      }
    }
  }

  suspend fun postRecordingDuration(sessionId: SessionId, duration: Duration): ApiResult<Unit> {
    return postSessionData(sessionId,"recordingLength", Json.encodeToString(duration.inWholeMilliseconds))
  }

  suspend fun ping() : ApiResult<Unit> {
    val url = "$baseURL/ping"
    return retryResult(limitAttempts(3)) {
      runCatching {
        log.debug { "Requesting: $url" }
        val response = client.post(url) {
        }
        if(response.status.isSuccess()) {
          // Nothing more to do
        } else {
          throw Throwable(response.status.toString())
        }
      }.mapError { InternalApiError("Error pinging API server: POST $url",it).also { log.error { "$it"} } }
    }
  }
  private suspend fun postSessionData(sessionId: SessionId, endpoint: String, jsonString: String) : ApiResult<Unit> {
    val url = "$baseURL/sessions/${sessionId.value}/$endpoint"
    return retryResult(limitAttempts(3)) {
      runCatching {
        log.debug { "Requesting: $url" }
        val response = client.post(url) {
          contentType(ContentType.Application.Json)
          setBody(jsonString)
        }
        if(response.status.isSuccess()) {
          // Nothing more to do
        } else {
          throw Throwable(response.status.toString())
        }
      }.mapError { InternalApiError("Error submitting session data ($jsonString) to: POST $url",it).also { log.error { "$it"} } }
    }
  }

  suspend fun postStopRecordReason(sessionId: SessionId, recordStopReason: RecordingStopReason) : ApiResult<Unit> =
    postSessionData(sessionId, "recordStopReason", Json.encodeToString(recordStopReason))

  suspend fun postConfirmationAnswer(sessionId: SessionId, confirmation: Confirmation) : ApiResult<Unit> {
    // No need to handle Replay case
    val confirmationValue = when(confirmation) {
      Confirmation.REPLAY -> return Ok(Unit)
      Confirmation.YES,
      Confirmation.NO,
      Confirmation.TIMEOUT -> confirmation.value
    }
    return postSessionData(sessionId,"confirmationAnswer",Json.encodeToString(confirmationValue))
  }

  suspend fun postSessionState(state: State) {
    // No Idea why the IDE detects an error in this when statement
    // The when statement is already exhaustive and doesn't need an else
    // And the class compiles without issue
    val endpoint = when(state) {
      is State.Init -> "init"
      is State.Idle -> "idle"
      is State.WelcomePrompt -> "welcome"
      is State.Recording -> "recording"
      is State.ConfirmationPrompt -> "confirmation"
      is State.ReplayAudio -> "replay"
      is State.Upload -> null
      is State.MobileEntryPrompt -> "questionnaireShare"
      is State.ThankYouPrompt -> "thankYouPrompt"
      is State.AudioError -> "audioError"
      is State.NetworkError -> null
      is State.HardwareError -> null
    } ?: return
    val url = "$baseURL/sessions/${state.sessionId.value}/$endpoint"
    retryResult(limitAttempts(3)) {
      runCatching {
        log.debug { "Requesting: $url" }
        val response = client.post(url)
        if(response.status.isSuccess()) {
          response.body<SessionId>()
        } else {
          log.error { response.status }
          throw Throwable(response.status.description)
        }
      }.mapError { InternalApiError("Error requesting $url",it) }
    }
  }



}