package querki.tools

import scala.collection.concurrent.TrieMap

import querki.globals._

import querki.ecology._
import querki.time._
import querki.util.Config

class ProfilerEcot(e:Ecology) extends QuerkiEcot(e) with Profiler {
  
  override def term() = {
    if (!profiles.isEmpty) {
      // We've done some profiling, so print out the results.
      QLog.info("Profile results:")
      val allProfiles = profiles.values.toSeq.sortBy(_.profile.name)
      allProfiles.foreach { profile =>
        QLog.info(s"${profile.profile.name}: ${profile.runs} runs, averaging ${(profile.time.getMillis / profile.runs)} ms")
      }
    }
  }

  private case class ProfileRecord(profile:ProfileHandleImpl, runs:Int, time:Duration)
  
  // IMPORTANT: this is a synchronous data structure, from Scala's core libraries! This is to allow
  // multi-threaded updates. This is a bit of a cheat in our usually Actor-centric architecture, to
  // avoid having Actor messages from profiling overwhelm what we're trying to measure.
  private val profiles = TrieMap.empty[ProfileHandleImpl, ProfileRecord]

  def createHandle(name:String):ProfileHandle = {
    if (Config.getBoolean(s"querki.profile.$name", false))
      ProfileHandleImpl(this, name)
    else
      NullProfileHandle
  }
  
  private [tools] def recordRun(instance:ProfileInstanceImpl):Unit = {
    val profile = instance.handle
    val time = new Duration(instance.startTime, DateTime.now)
    profiles.get(profile) match {
      case Some(record) => {
        profiles += (profile -> record.copy(runs = record.runs + 1, time = record.time + time))
      }
      case None => profiles += (profile -> ProfileRecord(profile, 1, time))
    }
  }
}

private [tools] case object NullProfileHandle extends ProfileHandle {
  def start() = NullProfileInstance
  def profile[T](f: => T):T = f
}

private [tools] case object NullProfileInstance extends ProfileInstance {
  def stop() = {}
}

private [tools] case class ProfileHandleImpl(ecot:ProfilerEcot, name:String) extends ProfileHandle {
  def start():ProfileInstance = {
    new ProfileInstanceImpl(this, DateTime.now)
  }
  
  def profile[T](f: => T):T = {
    val instance = start()
    try {
      f
    } finally {
      instance.stop()
    }
  }
}

private [tools] case class ProfileInstanceImpl(handle:ProfileHandleImpl, startTime:DateTime) extends ProfileInstance {
  def stop() = handle.ecot.recordRun(this)
}
