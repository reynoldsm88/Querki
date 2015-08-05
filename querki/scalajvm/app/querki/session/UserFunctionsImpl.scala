package querki.session

import scala.concurrent.Future
import scala.util.{Failure, Success}

import autowire._

import models.ThingId

import querki.api.{AutowireApiImpl, AutowireParams, BadPasswordException, MiscException}
import querki.data.{TID, SpaceInfo, UserInfo}
import querki.globals._
import querki.identity.UserLevel
import querki.spaces.messages._

/**
 * Implementation of the UserFunctions API. This basically covers most of the non-Space
 * stuff, that you can do without being *in* a Space.
 * 
 * @author jducoeur
 */
class UserFunctionsImpl(info:AutowireParams)(implicit e:Ecology) extends AutowireApiImpl(info, e) with UserFunctions {
  import UserFunctions._
  
  lazy val ClientApi = interface[querki.api.ClientApi]
  lazy val Core = interface[querki.core.Core]
  lazy val SpaceOps = interface[querki.spaces.SpaceOps]
  lazy val System = interface[querki.system.System]
  lazy val UserAccess = interface[querki.identity.UserAccess]
  
  lazy val spaceManager = SpaceOps.spaceManager
  
  def doRoute(req:Request):Future[String] = route[UserFunctions](this)(req)
  
  implicit def spaceDetails2Info(details:SpaceDetails):SpaceInfo = {
    SpaceInfo(TID(details.id.toThingId), Some(details.handle), details.display, "", details.ownerHandle)
  }
  
  def listSpaces():Future[AllSpaces] = {
    spaceManager.requestFor[MySpaces](ListMySpaces(user.id)) map { mySpaces =>
      AllSpaces(mySpaces.ownedByMe.map(spaceDetails2Info), mySpaces.memberOf.map(spaceDetails2Info))
    }
  }
  
  def accountInfo():Future[AccountInfo] = {
    // TODO: this is doing a direct DB access, which is by definition ugly.
    val pairOpt = UserAccess.getIdentity(ThingId(user.mainIdentity.handle))
    pairOpt match {
      case Some((identity, level)) => {
        Future.successful(AccountInfo(identity.handle, identity.name, identity.email.addr, level))
      }
      case None => throw new Exception(s"Somehow tried to get the accountInfo for unknown user ${user.mainIdentity.handle}!")
    }    
  }
  
  def changePassword(oldPassword:String, newPassword:String):Future[Unit] = {
    // TODO: also currently icky and blocking:
    if (newPassword.length() < 8)
      throw new MiscException("New password is too short!")
    
    val handle = user.mainIdentity.handle
    val checkedLogin = UserAccess.checkQuerkiLogin(handle, oldPassword)
    checkedLogin match {
      case Some(user) => {
        val identity = user.identityByHandle(handle).get
        val newUser = UserAccess.changePassword(rc.requesterOrAnon, identity, newPassword)
        Future.successful(())
      }
      
      case _ => throw new BadPasswordException()
    }    
  }
  
  def changeDisplayName(newDisplayName:String):Future[UserInfo] = {
    if (newDisplayName.length() == 0)
      throw new MiscException("Trying to set an empty Display Name!")

    UserAccess.changeDisplayName(user, user.mainIdentity, newDisplayName) map { newUser =>
      ClientApi.userInfo(Some(newUser)).get
    }
  }
  
  def createSpace(name:String):Future[SpaceInfo] = {
    if (name.length() == 0)
      throw new MiscException("Trying to create a Space with an empty name!")
    
    if (!user.canOwnSpaces)
      throw new MiscException("You aren't allowed to create Spaces!")
    
    // This will throw an exception if the name isn't legal. The UI *should* be screening this,
    // so just let the exception happen:
    Core.NameProp.validate(name, System.State)
    
    SpaceOps.spaceManager ? CreateSpace(user, name) map {
      case info:querki.spaces.messages.SpaceInfo => {
        ClientApi.spaceInfo(info)
      }
      case ThingError(ex, _) => throw ex
    }
  }
}
