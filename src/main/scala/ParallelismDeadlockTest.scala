import java.util.concurrent.{Callable, ExecutorService, Future, TimeUnit}

object ParallelismDeadlockTest extends App{
  /*
  We are asked to show that any fixed-size thread pool can be made to deadlock given this implementation

  Since the submission blocks on a call to another Par, that means that for a fixed sized thread pool of size n,
  if there are more than n forks, then deadlock will occur because the n+1 submission will not complete because it is
  not scheduled, while the n jobs already submitted will be waiting on the n+1 job to finish before it can complete.
   */
}


type Par[A] = ExecutorService => MyFuture[A]

trait MyFuture[A] {
  def get: A
  def get(timeout: Long, unit: TimeUnit): A
  def cancel(evenIfRunning: Boolean): Boolean
  def isDone: Boolean
  def isCancelled: Boolean
}

object Par {
  val myES: ExecutorService = ???

  def unit[A](a: A): Par[A] = (es: ExecutorService) => UnitFuture(a)

  def fork[A](a: => Par[A]): Par[A] = ???

  def lazyUnit[A](a: => A): Par[A] = fork(unit(a))

  def convertToNano(time: Long, units: TimeUnit): Long = ???

  private case class UnitFuture[A](get: A) extends MyFuture[A] {
    def isDone = true
    def get(timeout: Long, units: TimeUnit) = get
    def isCancelled = false
    def cancel(evenIfRunning: Boolean): Boolean = false
  }

  private case class TimeoutFuture[A](a1: Par[A], a2: Par[A], fun: (A, A) => A) extends MyFuture[A] {
    def isDone = true
    def get = {
      val output1 = a1(myES)
      val output2 = a2(myES)

      while (!output1.isDone && !output2.isDone){}

      fun(output1.get, output2.get)
    }
    def get(timeout: Long, units: TimeUnit) = {

      val start = System.nanoTime()
      val output1 = a1(myES)
      val output2 = a2(myES)

      val callable3 = new Callable[A]{
        override def call: A = { fun(output1, output2)}
      }
      val output3 = myES.submit(callable3)

      val nanoTimeout = units match {
        case TimeUnit.DAYS => TimeUnit.DAYS.toNanos(timeout)
        case TimeUnit.HOURS => TimeUnit.HOURS.toNanos(timeout)
        case TimeUnit.MINUTES => TimeUnit.MINUTES.toNanos(timeout)
        case TimeUnit.SECONDS => TimeUnit.SECONDS.toNanos(timeout)
        case TimeUnit.MILLISECONDS => TimeUnit.MILLISECONDS.toNanos(timeout)
        case TimeUnit.MICROSECONDS => TimeUnit.MICROSECONDS.toNanos(timeout)
        case TimeUnit.NANOSECONDS => TimeUnit.NANOSECONDS.toNanos(timeout)
      }

      while(System.nanoTime() - start < nanoTimeout && !output3.isDone){}

      if (!output3.isDone) {
        output1.cancel(true)
        output2.cancel(true)
        output3.cancel(true)
        throw new RuntimeException("Future timed out")
      } else output3.get
    }
    def isCancelled = false
    def cancel(evenIfRunning: Boolean): Boolean = false
  }

  def map2[A, B, C](a: Par[A], b: Par[B])(f: (A, B) => C): Par[C] = {
    (es: ExecutorService) => {
      TimeoutFuture(a, b, f)
    }
  }

  def asyncF[A, B](f: A => B): A => Par[B] = {
    (a: A) => lazyUnit(a)
  }

  def sequence[A](ps: List[Par[A]]): Par[List[A]] = {
    ps.foldRight(Par(List()): Par[List[A]]){ case (e, acc) =>
      map2(e, acc)((x, y)=> x::y)
    }
  }

  def parFilter[A](as: List[A])(f: A => Boolean): Par[List[A]] = {
    unit(as.filter(f))
  }
}