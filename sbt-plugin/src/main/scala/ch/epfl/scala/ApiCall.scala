package ch.epfl.scala

import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Try

import sbt.Logger
import sbt.internal.util.MessageOnlyException

/** Support utilities for API calls. */
object ApiCall {

  val timeout: Duration = 30.seconds

  private val maxRetries: Int = 3
  private val retryBaseDelay: Duration = 4.seconds

  /** An HTTP 5XX response. */
  case class ServerError(status: Int, statusText: String, bodyAsString: String)
      extends Exception(s"Unexpected status $status $statusText with body:\n$bodyAsString")

  /**
   * Retries `attempt` on a [[ServerError]], applying exponential back-off.
   * Non-server errors are propagated immediately without retrying.
   */
  def retryOnServerError[A](
      log: Logger,
      retriesLeft: Int = maxRetries,
      delay: Duration = retryBaseDelay
  )(attempt: => Try[A]): Try[A] =
    attempt.recoverWith {
      case ServerError(status, statusText, _) if retriesLeft > 0 =>
        log.warn(
          s"API responded with $status ($statusText). Retrying in ${delay.toMillis}ms ($retriesLeft retries left)."
        )
        Thread.sleep(delay.toMillis)
        retryOnServerError(log, retriesLeft - 1, delay * 2)(attempt)
      case err @ ServerError(_, _, _) =>
        Failure(new MessageOnlyException(err.getMessage))
    }
}
