import cats.NonEmptyTraverse
import cats.data.NonEmptyList
import cats.data._

import scala.util.Random
import cats.effect._
import cats.effect.implicits._
import cats.effect.kernel.Outcome
import cats.implicits._

import scala.concurrent.duration._

case class Data(source: String, body: String)

object RaceForSuccess extends IOApp {

  def provider(name: String)(implicit timer: Temporal[IO]): IO[Data] = {
    val proc = for {
      dur <- IO { Random.nextInt(500) }
      _ <- IO.sleep { (100 + dur).millis }
      _ <- IO { if (Random.nextBoolean()) throw new Exception(s"Error in $name") }
      txt <- IO { Random.alphanumeric.take(16).mkString }
    } yield Data(name, txt)

    proc.guaranteeCase {
      case Outcome.Succeeded(f_) => IO.println(s"$name request finished")
      case Outcome.Canceled()    => IO.println(s"$name request canceled")
      case Outcome.Errored(e)    => IO.println(s"$name errored")
    }
  }

  case class CompositeException(exs: NonEmptyList[Throwable])
      extends Exception("All race candidates have failed") {
    override def getMessage: String = exs.toList.mkString(": ")
  }

  def raceToSuccess[F[_]: Concurrent, R[_]: NonEmptyTraverse, A](ios: R[F[A]]): F[A] = {
    ios.reduce { case (l, r) =>
      Concurrent[F].racePair(l, r).flatMap {
        case Left((oc, f)) =>
          oc match {
            case Outcome.Succeeded(fa) => f.cancel *> fa
            case Outcome.Errored(ea) =>
              f.join.flatMap {
                case Outcome.Succeeded(fa) => fa
                case Outcome.Errored(eb) =>
                  Concurrent[F].raiseError(CompositeException(NonEmptyList.of(ea, eb)))
              }
          }
        case Right((f, oc)) =>
          oc match {
            case Outcome.Succeeded(fb) => f.cancel *> fb
            case Outcome.Errored(eb) =>
              f.join.flatMap {
                case Outcome.Succeeded(fa) => fa
                case Outcome.Errored(ea) =>
                  Concurrent[F].raiseError(CompositeException(NonEmptyList.of(eb, ea)))
              }
          }
      }
    }
  }

  val methods: NonEmptyList[IO[Data]] = NonEmptyList
    .of(
      "memcached",
      "redis",
      "postgres",
      "mongodb",
      "hdd",
      "aws"
    )
    .map(provider)

  def run(args: List[String]): IO[ExitCode] = {
    def oneRace = raceToSuccess(methods)
      .flatMap(a => IO(println(s"Final result is $a")))
      .handleErrorWith(err => IO(err.printStackTrace()))

    oneRace.replicateA(5).as(ExitCode.Success)
  }

}
