package querki.imexport

import scala.concurrent.{Future, Promise}

import akka.actor._

import org.querki.requester._

import models._
import Thing._

import querki.api.AutowireApiImpl
import querki.globals._
import querki.spaces.{Space}
import querki.spaces.messages._
import querki.types.ModelTypeBase
import querki.values.{ElemValue, RequestContext, QValue}

/**
 * This object is responsible for taking the XML representation of a Space and importing it into
 * the local Querki instance as a new Space.
 * 
 * Note that this isn't standalone -- it assumes that it is mixed in with an AutowireApiImpl,
 * and it interacts *heavily* with the SpaceManager and the new Space. As such, it is intended to
 * be one-shot, created for each operation.
 * 
 * @author jducoeur
 */
private [imexport] class ImporterImpl { self:AutowireApiImpl =>
  
  lazy val Core = interface[querki.core.Core]
  lazy val SpaceOps = interface[querki.spaces.SpaceOps]
  lazy val SpacePersistenceFactory = interface[querki.spaces.SpacePersistenceFactory]
  lazy val Types = interface[querki.types.Types]
  
  lazy val LinkType = Core.LinkType
  
  def createSpaceFromXml(name:String, xml:String) = {
    // Read the XML in its raw form, and build a SpaceState from that. This is only the beginning
    // of the process, though -- then we need to actually import all those Things into the DB, and
    // reassign all of the OIDs.
    val rawState = new RawXMLImport(rc)(ecology).readXML(xml)
  }
  
  /**
   * The importers start by building a faux SpaceState, representing the data to import. Now, we
   * actually build up a real Space from that.
   */
  private def createSpaceFromImported(name:String)(implicit imp:SpaceState):Future[Unit] = {
    // Note that this whole process is a really huge RequestM composition, and it isn't as pure
    // functional as it might look -- there are a bunch of side-effecting data structures involved,
    // for simplicity.
    // TODO: someday, once we have a proper websocket connection to the client, this should send
    // updates back up as these steps execute:
    for {
      // First, we create the new Space:
      SpaceInfo(spaceId, canon, display, ownerHandle) <- SpaceOps.spaceManager.request(CreateSpace(user, name))
      // IMPORTANT: we are creating a *local* version of the Space Actor, under the aegis of this
      // request, for purposes of this setup process. Afterwards, we'll shut this Actor down and hand
      // off to more normal machinery.
      // TODO: how can we intercept all failures, and make sure that this Actor gets shut down if
      // something goes wrong? Keep in mind that the process is wildly async, so we can't just do
      // try/catch.
      spaceActor = context.actorOf(Space.actorProps(ecology, SpacePersistenceFactory, Actor.noSender, spaceId))
      // Create all of the Models and Instances, so we have all their OIDs in the map.
      mapWithThings <- createThings(spaceActor, spaceId, imp.things.values.toSeq.sorted(new ModelOrdering(imp)), Map.empty)
      mapWithTypes <- createModelTypes(spaceActor, spaceId, imp.types.values.toSeq, mapWithThings)
      mapWithProps <- createProperties(spaceActor, spaceId, imp.spaceProps.values.toSeq, mapWithTypes)
      dummy1 <- setPropValues(spaceActor, spaceId, imp.things.values.toSeq, mapWithProps)
      dummy2 <- setSpaceProps(spaceActor, spaceId, mapWithProps)
    }
      yield ()
  }
  
  // While we're doing the import, we need to map from "temporary" OIDs to real ones
  type ExtId = OID
  type IntId = OID
  type IDMap = Map[ExtId, IntId]
  
  /**
   * Sort the Things so that Models come before their Instances.
   * 
   * This might belong somewhere more generally accessible.
   */
  class ModelOrdering(space:SpaceState) extends scala.math.Ordering[Thing] {
    implicit val e = ecology
    implicit val s = space
    
    def compare(x:Thing, y:Thing):Int = {
      if (x.isAncestor(y.id))
        1
      else if (y.isAncestor(x.id))
        -1
      else
        x.id.raw.compare(y.id.raw)
    }
  }

  /**
   * Step 1: create all of the Models and Instances, empty, so that we have their real OIDs.
   */
  private def createThings(actor:ActorRef, spaceId:OID, things:Seq[Thing], idMap:IDMap)(implicit imp:SpaceState):RequestM[IDMap] = {
    things.headOption match {
      case Some(thing) => {
        actor.request(CreateThing(user, spaceId, Kind.Thing, idMap(thing.model), Thing.emptyProps)) flatMap {
          case ThingFound(intId, state) => {
            createThings(actor, spaceId, things.tail, idMap + (thing.id -> intId))
          }
          case ThingError(ex, _) => {
            RequestM.failed(ex)
          }
        }
      }
      case None => RequestM.successful(idMap)
    }    
  }
  
  /**
   * Step 2: create all of the Model Types.
   */
  private def createModelTypes(actor:ActorRef, spaceId:OID, modelTypes:Seq[PType[_]], idMap:IDMap)(implicit imp:SpaceState):RequestM[IDMap] = {
    modelTypes.headOption match {
      case Some(pt) => {
        pt match {
          case mt:ModelTypeBase => {
            // Translate the link to the underlying Model:
            val props = mt.props + (Types.ModelForTypeProp(idMap(mt.basedOn)))
            actor.request(CreateThing(user, spaceId, Kind.Type, Core.UrType, props)) flatMap {
              case ThingFound(intId, state) => {
                createThings(actor, spaceId, modelTypes.tail, idMap + (mt.id -> intId))
              }
              case ThingError(ex, _) => {
                RequestM.failed(ex)
              }
            }
          }
          case _ => {
            QLog.error(s"Somehow trying to import a non-Model Type: $pt")
            createThings(actor, spaceId, modelTypes.tail, idMap)
          }
        }
      }
      case None => RequestM.successful(idMap)
    }     
  }
  
  // These are the Properties whose values can not be set yet, indexed by the ID of their Model Type:
  // TODO: is this sufficient? What if this Property is based on a Model Type that is itself based on
  // three more Model Types?
  private var deferredProperties = Map.empty[ExtId, Seq[ExtId]]
  
  // These are the values waiting on deferred Properties:
  case class DeferredPropertyValue(propId:ExtId, thingId:ExtId, v:QValue) {
    
  }
  private var deferredPropertyValues = Map.empty[ExtId, Seq[DeferredPropertyValue]]
  
  /**
   * Step 3: create all of the Properties.
   */
  private def createProperties(actor:ActorRef, spaceId:OID, props:Seq[AnyProp], idMap:IDMap)(implicit imp:SpaceState):RequestM[IDMap] = {
    props.headOption match {
      case Some(prop) => {
        // Take note of any Properties that can't be resolved yet:
        prop.pType match {
          case mt:ModelTypeBase => {
            val propsForThisModel = deferredProperties.get(mt.basedOn) match {
              case Some(otherProps) => otherProps :+ prop.id
              case None => Seq(prop.id)
            }
            deferredProperties += (mt.basedOn -> propsForThisModel)
          }
          case _ => 
        }
        
        // Note that we are currently assuming you aren't defining meta-Properties that you are putting on
        // your Properties.
        // TODO: cope with this edge case. In that case, we will need to add those in another pass, or something.
        actor.request(CreateThing(user, spaceId, Kind.Property, querki.core.MOIDs.UrPropOID, prop.props)) flatMap {
          case ThingFound(intId, state) => {
            createProperties(actor, spaceId, props.tail, idMap + (prop.id -> intId))
          }
          case ThingError(ex, _) => {
            RequestM.failed(ex)
          }
        }         
      }
      case None => RequestM.successful(idMap)
    }
  }
  
  /**
   * This looks at the properties of t. It adds a deferral for any Properties that we
   * can't yet deal with, and returns the rest. It also translates the Property IDs.
   * 
   * TODO: this is not yet sufficient for dealing with Model Types. If it's a Model Type
   * value, we need to dive into it and translate all of the nested values in there.
   */
  private def translateProps(t:Thing, idMap:IDMap)(implicit imp:SpaceState):Thing.PropMap = {
    t.props filter { pair =>
      val (propId, qv) = pair
      val isDeferred = deferredProperties.contains(propId)
      val deferredVals =
        if (isDeferred)
          deferredPropertyValues(propId) :+ DeferredPropertyValue(propId, t.id, qv)
        else
          Seq(DeferredPropertyValue(propId, t.id, qv))
      deferredPropertyValues += (propId -> deferredVals)
      isDeferred
    } map { pair =>
      val (propId, qv) = pair
      val realQv = imp.prop(propId) match {
        case Some(prop) => {
          prop.confirmType(Core.LinkType) match {
            case Some(linkProp) => {
              // This Property is made of Links, so we need to map all of them to the new values:
              val raw = qv.rawList(Core.LinkType)
              val translatedLinks = raw.map { extId => ElemValue(idMap(extId), Core.LinkType) }
              qv.cType.makePropValue(translatedLinks, Core.LinkType)
            }
            case None => qv
          }
        }
        case None => qv  // TODO: This is weird and buggy! What should we do with it?
      }
        
      (idMap(propId), qv)
    }
  }
  
  /**
   * Step 4: Okay, everything is created; now fill in the values.
   */
  private def setPropValues(actor:ActorRef, spaceId:OID, things:Seq[Thing], idMap:IDMap)(implicit imp:SpaceState):RequestM[Unit] = {
    things.headOption match {
      case Some(thing) => {
        actor.request(ChangeProps(user, spaceId, idMap(thing.id), translateProps(thing, idMap))) flatMap {
          case ThingFound(intId, state) => {
            // TODO: if this was the base of a Model Type, we need to recurse into deferredPropertyValues and
            // set those as well. We also need to clear deferredProperties for this.
            setPropValues(actor, spaceId, things.tail, idMap)
          }
          case ThingError(ex, _) => {
            RequestM.failed(ex)
          }
        }
      }
      case None => RequestM.successful(())
    }
  }
  
  /**
   * Step 5: set the properties on the Space itself.
   */
  private def setSpaceProps(actor:ActorRef, spaceId:OID, idMap:IDMap)(implicit imp:SpaceState):RequestM[Any] = {
    actor.request(ChangeProps(user, spaceId, spaceId, translateProps(imp, idMap)))
  }
}
