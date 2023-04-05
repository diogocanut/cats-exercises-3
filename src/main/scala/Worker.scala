import cats.effect.std.Queue
import cats.effect._
import cats.effect.implicits._
import cats.implicits._

import scala.util.Random
import scala.concurrent.duration._

object Worker extends IOApp {

  type Worker[A, B, F[_]] = A => F[B]

  def mkWorker[F[_]: Async: Concurrent](
      id: Int
  )(implicit timer: Temporal[F]): F[Worker[Int, Int, F]] =
    Ref[F].of(0).map { counter =>
      def simulateWork: F[Unit] =
        Async[F].delay(50 + Random.nextInt(450)).map(_.millis).flatMap(timer.sleep)

      def report: F[Unit] =
        counter.get.flatMap(i => Concurrent[F].pure(println(s"Total processed by $id: $i")))

      x =>
        simulateWork >>
          counter.update(_ + 1) >> report >>
          Async[F].pure(x + 1)
    }

  trait WorkerPool[A, B, F[_]] {
    def exec(a: A): F[B]

    def add(worker: Worker[A, B, F]): F[Unit]

    def removeAll: F[Unit]
  }

  object WorkerPool {
    def of[A, B, F[_]: Async: Concurrent](fs: List[Worker[A, B, F]]): F[WorkerPool[A, B, F]] = {
      for {
        ref <- Ref[F].of(fs)
        capacity = 100
        queue <- Queue.bounded[F, Worker[A, B, F]](capacity)
        _ <- fs.traverse(queue.offer)
      } yield new WorkerPool[A, B, F] {
        override def exec(a: A): F[B] =
          for {
            worker <- queue.take
            b <- worker.apply(a).guarantee(putBack(worker))
          } yield b

        def add(worker: Worker[A, B, F]): F[Unit] =
          ref.update(_ :+ worker) >> queue.offer(worker)

        def removeAll: F[Unit] =
          ref.set(List.empty)

        private def putBack(worker: Worker[A, B, F]): F[Unit] =
          for {
            contains <- ref.get.map(_.contains(worker))
            _ <- if (contains) queue.offer(worker) else Async[F].unit
          } yield ()
      }
    }
  }

  val testPool: IO[WorkerPool[Int, Int, IO]] =
    List
      .range(0, 10)
      .traverse(mkWorker[IO])
      .flatMap(WorkerPool.of[Int, Int, IO])

  def run(args: List[String]): IO[ExitCode] =
    for {
      pool <- testPool
      _ <- pool.exec(42).replicateA(20)
      newWorker <- mkWorker[IO](10)
      _ <- pool.add(newWorker)
      _ <- pool.exec(42).replicateA(11)
    } yield ExitCode.Success
}
