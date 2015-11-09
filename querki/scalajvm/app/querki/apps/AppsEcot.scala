package querki.apps

import akka.pattern._
import akka.util.Timeout
import scala.concurrent.duration._

import models.{Property}

import querki.ecology._
import querki.globals._
import querki.spaces._
import querki.util.{Contributor, Publisher}

object MOIDs extends EcotIds(59) {
  val CanBeAppOID = moid(1)
  val CanManipulateAppsOID = moid(2)
}

/**
 * Private interface for use within the Apps subsystem.
 */
private [apps] trait AppsInternal extends EcologyInterface {
  def CanUseAsAppProp:Property[OID, OID]
}

/**
 * @author jducoeur
 */
class AppsEcot(e:Ecology) extends QuerkiEcot(e) with SpacePluginProvider with Apps with AppsInternal {
  import MOIDs._
  
  val AccessControl = initRequires[querki.security.AccessControl]
  
  lazy val ApiRegistry = interface[querki.api.ApiRegistry]
  lazy val SpaceChangeManager = interface[querki.spaces.SpaceChangeManager]
  lazy val SpaceOps = interface[querki.spaces.SpaceOps]
  
  override def postInit() = {
    // Some entry points are legal without login:
    ApiRegistry.registerApiImplFor[AppsFunctions, AppsFunctionsImpl](SpaceOps.spaceRegion, false)
    SpaceChangeManager.appLoader += new AppLoading
    SpaceChangeManager.registerPluginProvider(this)
  }
  
  /**
   * Called by each Space once, to instantiate its plugins. This is how we hook Space processing.
   */
  def createPlugin(space:SpaceAPI):SpacePlugin = new AppsSpacePlugin(space, ecology)
  
  class AppLoading extends Contributor[AppLoadInfo, Future[Seq[SpaceState]]] {
    def notify(evt:AppLoadInfo, sender:Publisher[AppLoadInfo, Future[Seq[SpaceState]]]):Future[Seq[SpaceState]] = {
      import AppLoadingActor._
      
      val AppLoadInfo(ownerIdentity, spaceId, space) = evt
      // TODO: this is arbitrary. Probably make it configurable? Keep in mind that this may kick
      // off recursive loads.
      implicit val timeout = Timeout(1 minute)
      
      // We do the actual work in a separate Actor:
      val loader = space.context.actorOf(AppLoadingActor.props(ecology, spaceId, ownerIdentity))
      (loader ? FetchApps).mapTo[AppStates] map { _.states }
    }
  }
    
  /***********************************************
   * PROPERTIES
   ***********************************************/
  
  lazy val CanUseAsAppProp = AccessControl.definePermission(CanBeAppOID, 
      "Can Use as an App",
      "These people are allowed to use this Space as an App. **Use with caution! These people will be able to see everything in this Space!**",
      Seq(AccessControl.OwnerTag),
      true)
  
  lazy val CanManipulateAppsPerm = AccessControl.definePermission(CanManipulateAppsOID, 
      "Can Manipulate Apps",
      "These people are allowed to add or remove Apps from this Space",
      Seq(AccessControl.OwnerTag),
      true)
  
  override lazy val props = Seq(
    CanUseAsAppProp,
    CanManipulateAppsPerm
  )
}