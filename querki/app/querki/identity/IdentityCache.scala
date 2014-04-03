package querki.identity

import akka.actor._

import models.{OID}

import querki.ecology._

/**
 * The front-end cache for Users and Identities. All interactions with these tables should
 * go through here, or at least inform this Cache about invalidating operations.
 * 
 * TODO: this is currently calling UserPersistence directly. In principle, it shouldn't be
 * doing do, since those are blocking operations. Instead, those calls should be doled out
 * to workers, so that they don't block other requests for the cache.
 */
private[identity] class IdentityCache(val ecology:Ecology) extends Actor with EcologyMember {
  
  import IdentityCache._
  
  lazy val UserAccess = interface[UserAccess]
  
  var identities = Map.empty[OID, Identity] 
  
  def receive = {
    case GetIdentityRequest(id) => {
      identities.get(id) match {
        case Some(identity) => sender ! IdentityFound(identity)
        case None => {
          UserAccess.getIdentity(id) match {
            case Some(identity) => {
              identities += (id -> identity)
              sender ! IdentityFound(identity)
            }
            case None => sender ! IdentityNotFound
          }
        }
      }
    }
    
    case GetIdentities(ids) => {
      val result = (Map.empty[OID, Identity] /: ids) { (curmap, id) =>
        identities.get(id) match {
          case Some(identity) => curmap + (id -> identity)
          case None => {
            UserAccess.getIdentity(id) match {
              case Some(identity) => {
                identities += (id -> identity)
                curmap + (id -> identity)
              }
              case None => curmap
            }
          }
        }
      }
      sender ! IdentitiesFound(result)
    }
    
  }
}

private[identity] object IdentityCache {
  case class GetIdentityRequest(id:OID)
  case class IdentityFound(identity:PublicIdentity)
  case object IdentityNotFound
  
  case class GetIdentities(ids:Seq[OID])
  case class IdentitiesFound(identities:Map[OID,Identity])
}