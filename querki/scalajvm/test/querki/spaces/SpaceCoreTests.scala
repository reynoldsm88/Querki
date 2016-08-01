package querki.spaces

import akka.actor.Actor.{noSender, Receive}
import akka.persistence._

import models._
import Kind.Kind
import Thing.{emptyProps, PropMap}
import querki.basic.MOIDs.SimpleThingOID
import querki.core.MOIDs.{UrPropOID, UrTypeOID}
import querki.globals._
import querki.identity.{Identity, PublicIdentity, User}
import querki.persistence._
import querki.spaces.messages._
import querki.test._
import querki.time._
import querki.values.QValue
  
case class HistoryRecord(sequenceNr:Long, msg:Any)

case class TestSpaceConfig(snapshotInterval:Option[Int])
  
/**
 * For testing, we use a version of SpaceCore built around the synchronous TCIdentity.
 */
class TestSpaceCore(val id:OID, testSpace:TestSpace, val config:Option[TestSpaceConfig] = None, initHistory:List[HistoryRecord] = List.empty)(implicit e:Ecology) extends SpaceCore[TCIdentity](TestRTCAble) {
  
  /**
   * Allow calling tests to override the Snapshot frequency, to ensure snapshots:
   */
  override def getSnapshotInterval = config.flatMap(_.snapshotInterval).getOrElse(100)
  
  /**
   * We don't currently expect this to be called during tests, although that might change.
   */
  def handleRequestResponse:Receive = ???
  
  /**
   * We don't expect this to be called either, at least not yet.
   */
  def stash():Unit = ???
  def unstashAll():Unit = {}
  
  /**
   * This is the "history" of "persisted" events, in reverse chronological order. (That is, most recent is
   * at the front.)
   */
  var history = initHistory
  
  def doPersist[A <: UseKryo](event:A)(handler: (A) => Unit) = {
    lastSequenceNr += 1
    history = HistoryRecord(lastSequenceNr, event) :: history
    handler(event)
  }
  
  var lastSequenceNr:Long = 0
  
  /**
   * This sends the given message back to sender.
   */
  def respond(msg:AnyRef) = {
    currentResponses = msg :: currentResponses
  }
  
  /**
   * The responses to the current message.
   */
  var currentResponses:List[AnyRef] = List.empty
  
  /**
   * Called by the test code. Returns the most recent response, if there were any.
   */
  def aroundReceive(msg:AnyRef):Option[AnyRef] = {
    currentResponses = List.empty
    receiveCommand(msg)
    currentResponses.headOption
  }
  
  /**
   * Give hooks an opportunity to chime in on this change.
   * 
   * TODO: can we do this for real, letting the rest of the Ecology play? Seems potentially problematic
   * in the synchronous unit tests, but I'm intrigued.
   */
  def offerChanges(who:User, modelId:Option[OID], thingOpt:Option[Thing], kind:Kind, propsIn:PropMap, changed:Seq[OID]):TCIdentity[ThingChangeRequest] = {
    // The null below is ugly, but I don't have an easy way to provide a Requester reference. How *should* we do this
    // in tests? We basically want to carry the TCIdentity abstraction further along.
    val tcr = ThingChangeRequest(who, null, state, noSender, modelId, None, kind, propsIn, changed)
    TestRTCAble.successful(tcr)
  }
  
  def allocThingId():TCIdentity[OID] = {
    TestRTCAble.successful(testSpace.toid())
  }
  
  def notifyUpdateState() = {
    // TODO: hook and test this?
  }
  
  def changeSpaceName(newName:String, newDisplay:String) = {}
  
  def saveSnapshot(snapshot:Any) = {
    val metadata = SnapshotMetadata(persistenceId, lastSequenceNr, DateTime.now.getMillis)
    val event = SnapshotOffer(metadata, snapshot)
    // Note that the snapshot *replaces* the rest of the history, intentionally. Playback should start
    // from here:
    history = HistoryRecord(lastSequenceNr, event) :: Nil
    receiveCommand(SaveSnapshotSuccess(metadata))
  }
  
  /**
   * If an initial history was provided, that's effectively the persistence log, so play it
   * before we do anything else.
   */
  if (!initHistory.isEmpty) {
    // Reverse it to get chrono order:
    val playHistory = initHistory.reverse
    playHistory.foreach { record =>
      lastSequenceNr = record.sequenceNr
      receiveRecover(record.msg)
    }
  }
  receiveRecover(RecoveryCompleted)
  
  /**
   * We don't currently expect this to be called in the test environment.
   */
  def recoverOldSpace():TCIdentity[Option[SpaceState]] = TestRTCAble.successful(None)
  
  def fetchOwnerIdentity():TCIdentity[PublicIdentity] = {
    TestRTCAble.successful(testSpace.owner.mainIdentity)
  }
}

/**
 * This is the base concept of a TestSpace that is dynamically building a TestSpaceCore.
 */
abstract class SpaceCoreSpaceBase()(implicit val ecology:Ecology) extends TestSpace {
  lazy val Types = interface[querki.types.Types]
  
  def sc:TestSpaceCore
  
  override def state = sc.state
  
  def !(msg:AnyRef) = sc.aroundReceive(msg)
  
  /**
   * Use this signature if you need to get a hold of the state after the change is made. Don't
   * over-use this -- there aren't many circumstances where you need it.
   */
  def addSomethingFull(name:String, kind:Kind, model:OID, propList:(OID, QValue)*):Option[AnyRef] = {
    val props = makePropFetcher(name, propList)
    this ! CreateThing(owner, sc.id, kind, model, props)
  }
  
  def addSomething(name:String, kind:Kind, model:OID, propList:(OID, QValue)*):OID = {
    val Some(ThingFound(oid, _)) = addSomethingFull(name, kind, model, propList:_*)
    oid
  }
  
  def addProperty[VT, RT](tpe:PType[VT] with PTypeBuilder[VT, RT], coll:Collection, name:String):PropRecord[VT, RT] = {
    val oid = addSomething(name, Kind.Property, UrPropOID,
      Core.CollectionProp(coll),
      Core.TypeProp(tpe)
    )
    PropRecord(oid, coll, tpe)
  }
  
  def addThing(name:String, model:OID, propList:(OID, QValue)*) = {
    addSomething(name, Kind.Thing, model, propList:_*)
  }
  
  def addSimpleThing(name:String, propList:(OID, QValue)*) = {
    addThing(name, SimpleThingOID, propList:_*)
  }
  
  def addSimpleModel(name:String, propList:(OID, QValue)*) = {
    addThing(name, SimpleThingOID,
      (Core.IsModelProp(true) +: propList):_*
    )
  }
  
  def addType(name:String, modelId:OID) = {
    val Some(ThingFound(oid, state)) = addSomethingFull(name, Kind.Type, UrTypeOID, Types.ModelForTypeProp(modelId))
    state.typ(oid).asInstanceOf[ModelType]
  }
  
  def changeThing(thingId:OID, propList:(OID, QValue)*) = {
    this ! ChangeProps(owner, sc.id, thingId, Map(propList:_*), true)    
  }
}

/**
 * This is a specialized version of TestSpace that creates a SpaceCore and wraps around that. It allows a
 * much richer set of interactions than the traditional CommonSpace, but requires some setup effort.
 */
class SpaceCoreSpace(implicit e:Ecology) extends SpaceCoreSpaceBase {
  def configOpt:Option[TestSpaceConfig] = None
  val world = new TestWorld
  val sc = new TestSpaceCore(toid(), this, configOpt)
}

/**
 * This version of SpaceCoreSpaceBase starts from a previous one, and replays its history as the starting
 * point. It is intended to demonstrate that history replays properly.
 */
class ReplayCoreSpace(oldSpace:SpaceCoreSpaceBase)(implicit e:Ecology) extends SpaceCoreSpaceBase {
  def world = oldSpace.world
  val sc = new TestSpaceCore(oldSpace.sc.id, this, oldSpace.sc.config, oldSpace.sc.history)
  override def makeOwner = oldSpace.owner
}

class SpaceCoreTests extends QuerkiTests {  
  "SpaceCoreSpace" should {
    "throw an exception if it doesn't start with InitialState" in {
      implicit val s = new SpaceCoreSpace

      intercept[Exception] {
        s ! CreateThing(s.owner, s.sc.id, Kind.Thing, SimpleThingOID, emptyProps)
      }
    }
  }
}
