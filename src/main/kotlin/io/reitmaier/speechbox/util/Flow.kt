package io.reitmaier.speechbox.util

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.selects.select
import kotlin.time.Duration


fun <T> Flow<T>.timeout(
  timeoutDelay: Duration,
  timeoutScope: CoroutineScope = CoroutineScope(Dispatchers.Default)
): Flow<T> {
  val upstreamFlow = this

  return flow {
    val collector = this

    // create new scope to create values channel in it
    // and to confine all the children coroutines there
    coroutineScope {

      // reroute original flow values into a channel that will be part of select clause
      val values = produce {
        upstreamFlow.collect { value ->
          send(TimeoutState.Value(value))
        }
      }

      // reference to latest used timeout scope so it can be cancelled later
      var latestTimeoutScope: ProducerScope<Unit>? = null

      // run in the loop until we get a confirmation that flow has ended
      var latestValue: TimeoutState = TimeoutState.Initial
      while (latestValue !is TimeoutState.Final) {

        // start waiting for timeout
        val timeout = timeoutScope.produce {
          latestTimeoutScope = this
          delay(timeoutDelay)
          send(Unit)
        }

        // whatever comes first decides our fate
        select<Unit> {

          // Two options:
          // 1. We got normal value - emission from upstream, cancel timeout scope
          // and emit it to downstream
          //
          // 2. We got null value - upstream flow was cancelled (and thus channel was closed),
          // still cancel timeout and set latest value to Done marker, killing the while loop
          values.onReceiveCatching {
            latestTimeoutScope?.cancel()
            val value = it.getOrNull()
            if (value!= null) {
              latestValue = value
              collector.emit(value.value)
            } else {
              latestValue = TimeoutState.Final.Done
            }
          }

          // we got a timeout! Set latest value to Timeout marker, killing the while loop
          timeout.onReceiveCatching {
            val value = it.getOrNull()
            if (value != null) {
              latestValue = TimeoutState.Final.Timeout
            }
          }
        }
      }

      // additional cancel in case upstream flow finished without emitting anything
      latestTimeoutScope?.close()

      // if latest value is a Timeout marker, throw timeout exception
      if (latestValue is TimeoutState.Final.Timeout) {
        throw TimeoutException()
      }
    }
  }
}

private sealed class TimeoutState {

  sealed class Final: TimeoutState() {
    object Done: Final()
    object Timeout: Final()
  }

  object Initial: TimeoutState()
  data class Value<T>(val value: T): TimeoutState()
}

class TimeoutException: RuntimeException("Timed out waiting for emission")

