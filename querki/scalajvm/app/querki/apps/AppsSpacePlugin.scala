package querki.apps

import scala.util.{Failure, Success}

import akka.actor._

import models.{ModelPersistence, Thing}
import models.Thing._

import querki.globals._
import querki.identity.{IdentityPersistence, User}
import querki.spaces._
import SpaceMessagePersistence.DHAddApp
import querki.spaces.messages._
import querki.time._
import querki.util.{PublicException, UnexpectedPublicException}
import querki.values.{RequestContext, SpaceVersion}

/**
 * This code runs *inside* the Space Actor, as a plugin. It has important constraints, as described in
 * SpaceAPI.
 * 
 * @author jducoeur
 */
class AppsSpacePlugin[RM[_]](api:SpaceAPI[RM], rtc:RTCAble[RM], implicit val ecology:Ecology) 
  extends SpacePlugin(api, rtc) with ModelPersistence with IdentityPersistence with querki.types.ModelTypeDefiner with EcologyMember with AppsPure
{
  lazy val AccessControl = interface[querki.security.AccessControl]
  lazy val Apps = interface[Apps]
  lazy val SpaceOps = interface[querki.spaces.SpaceOps]
  
  implicit def rm2rtc[A](rm:RM[A]) = rtc.toRTC(rm)
  
  def modelsToShadow(app:SpaceState):Seq[Thing] = {
    app.things.values.filter(_.isModel(app)).toSeq
  }
  
  def addApp(req:User, appId:OID, appVersion:SpaceVersion)(state:SpaceState):RM[ChangeResult] = {
    // Belt-and-suspenders security check that the current user is allowed to do this. In theory, this
    // can only fail if something has changed significantly:
    if (!AccessControl.hasPermission(Apps.CanManipulateAppsPerm, state, req, state))
      throw new PublicException("Apps.notAllowed")
    
    // Check that this App isn't already present for this Space:
    if (state.appInfo.find(_._1 == appId).isDefined)
      throw new PublicException("Apps.alreadyThere")
    
    // Okay -- load the app:
    for {
      app <- api.loadAppVersion(appId, appVersion, state.allApps)
      // Secondary check: is this App willing to be used?
      _ = {
        if (!AccessControl.hasPermission(Apps.CanUseAsAppPerm, app, state.ownerIdentity.get.id, app))
          throw new PublicException("Apps.notAnApp")
      }
      appModels = modelsToShadow(app)
      shadowIds <- api.allocThingIds(appModels.size)
      idPairs = appModels.map(_.id).zip(shadowIds)
      idMapping = Map(idPairs:_*)
    }
      yield {
        implicit val s = state
        val dhApp = dh(app)
        val dhParents = app.allApps.values.toSeq.map(dh(_))
        val time = DateTime.now
        val msg = DHAddApp(req, time, dhApp, dhParents, idMapping)
        ChangeResult(List(msg), Some(appId), addFilledAppPure(app, idMapping, time)(s))
      }    
  }
  
  /**
   * This will be called during the Actor's receive loop, and provides supplementary
   * handlers particular to Apps.
   */
  def receive:Actor.Receive = {
    // Note that this can be called cross-node, so we specifically do *not* use
    // runAndSendResponse. (Since that tries to send the new State.)
    case SpacePluginMsg(req, _, AddApp(appId, appVersion)) => {
      api.runChanges(Seq(addApp(req, appId, appVersion)))(api.currentState).onComplete {
        case Success(_) => api.respond(AddAppResult(None))
        case Failure(th) => {
          th match {
            case ex:PublicException => api.respond(AddAppResult(Some(ex)))
            case ex => {
              QLog.error(s"AddApp received an unexpected exception", ex)
              api.respond(ThingError(UnexpectedPublicException))
            }
          }
        }
      }
    }
  }
}

/**
 * Sent from AppsFunctions to the Plugin, so that app-adding can happen inside the Space's own context.
 * To add the current version of the App, use Int.MaxValue as the version.
 */
private [apps] case class AddApp(appId:OID, appVersion:SpaceVersion)
/**
 * Response from AddApp. Should be considered successful unless there is an Exception here.
 */
private [apps] case class AddAppResult(exceptionOpt:Option[PublicException])