package nelson

import cats.effect.{Effect, IO, Timer}
import cats.syntax.functor._
import cats.syntax.monadError._

import fs2.{Pipe, Sink, Stream}

import java.util.concurrent.{ScheduledExecutorService, TimeoutException}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

object CatsHelpers {
  implicit val catsIOScalazInstances: scalaz.Catchable[IO] =
    new scalaz.Catchable[IO] {
      def attempt[A](fa: IO[A]): IO[scalaz.\/[Throwable, A]] = fa.attempt.map {
        case Left(a) => scalaz.-\/(a)
        case Right(b) => scalaz.\/-(b)
      }

      def fail[A](err: Throwable): IO[A] = IO.raiseError(err)
    }

  implicit class NelsonEnrichedIO[A](val io: IO[A]) extends AnyVal {
    /** Run `other` if this IO fails */
    def or(other: IO[A]): IO[A] = io.attempt.flatMap {
      case Right(a) => IO.pure(a)
      case Left(e)  => other
    }

    /** Fail with error if the result of the IO does not satsify the predicate
      *
      * Taken from https://github.com/scalaz/scalaz/blob/series/7.3.x/concurrent/src/main/scala/scalaz/concurrent/Task.scala
      */
    def ensure(failure: => Throwable)(f: A => Boolean): IO[A] =
      io.flatMap(a => if (f(a)) IO.pure(a) else IO.raiseError(failure))

    def timed(timeout: FiniteDuration)(implicit ec: ExecutionContext, schedulerES: ScheduledExecutorService): IO[A] =
      IO.race(
        Timer[IO].sleep(timeout).as(new TimeoutException(s"Timed out after ${timeout.toMillis} milliseconds"): Throwable),
        io
      ).rethrow
  }

  private def sinkW[F[_], W, O](actualSink: Sink[F, W]): Sink[F, Either[W, O]] =
    stream => actualSink(stream.collect { case Left(e) => e })

  private def pipeO[F[_], W, O, O2](actualPipe: Pipe[F, O, O2]): Pipe[F, Either[W, O], Either[W, O2]] =
    _.flatMap {
      case Left(a)  => Stream.emit(Left(a))
      case Right(b) => actualPipe(Stream.emit(b)).map(Right(_))
    }

  implicit class NelsonEnrichedWriterStream[F[_], W, O](val stream: Stream[F, Either[W, O]]) {
    def observeW(sink: Sink[F, W])(implicit F: Effect[F], ec: ExecutionContext): Stream[F, Either[W, O]] =
      stream.observe(sinkW(sink))

    def stripW: Stream[F, O] = stream.collect { case Right(o) => o }

    def throughO[O2](pipe: Pipe[F, O, O2]): Stream[F, Either[W, O2]] =
      stream.through(pipeO(pipe))
  }
}
