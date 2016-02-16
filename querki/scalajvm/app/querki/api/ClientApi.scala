package querki.api

import akka.actor._

import upickle._
import autowire._

import models.{AsOID, HtmlWikitext}

import querki.globals._

import querki.core.NameUtils
import querki.data._
import querki.ecology._
import querki.identity.{PublicIdentity, User}
import querki.tags.IsTag
import querki.types.ModelTypeBase
import querki.values.{QLRequestContext, RequestContext}

class ClientApiEcot(e:Ecology) extends QuerkiEcot(e) with ClientApi
{
  
  lazy val AccessControl = interface[querki.security.AccessControl]
  lazy val ApiRegistry = interface[querki.api.ApiRegistry]
  lazy val Apps = interface[querki.apps.Apps]
  lazy val Basic = interface[querki.basic.Basic]
  lazy val Conventions = interface[querki.conventions.Conventions]
  lazy val DataModelAccess = interface[querki.datamodel.DataModelAccess]
  lazy val Editor = interface[querki.editing.Editor]
  
  var _anonHandler:Option[ActorRef] = None
  lazy val anonHandler = _anonHandler.get

  override def createActors(createActorCb:CreateActorFunc):Unit = {
    _anonHandler = createActorCb(AnonymousApiRouter.actorProps(ecology), "AnonHandler")
  }
  
  override def postInit() = {
    ApiRegistry.registerApiImplFor[CommonFunctions, CommonFunctionsImpl](anonHandler, false)
  }

  implicit def thing2TID(t:Thing) = TID(t.id.toThingId.toString)
  implicit def OID2TID(oid:OID) = TID(oid.toThingId.toString)
  
  def thingInfo(t:Thing, rc:RequestContext)(implicit state:SpaceState):Future[ThingInfo] = {
      val user = rc.requesterOrAnon
      val editable = AccessControl.canEdit(state, user, t.id)
      val isModel = t.isModel
      val importedFrom =
        if (t.spaceId == state.id)
          None
        else
          spaceInfo(state.getApp(t.spaceId), rc)
      t.nameOrComputedWiki(rc, state) map { name =>
        ThingInfo(
          t, 
          t.linkName, 
          name,
          t.model,
          t.kind,
          isModel,
          editable,
          // We allow deleting Properties at this level:
          editable && DataModelAccess.isDeletable(t, allowIfProp = true),
          isModel && AccessControl.canCreate(state, user, t),
          t.isInstanceOf[IsTag],
          importedFrom)
      }
  }
  
  case class PermSet(thingId:OID, state:SpaceState, user:User, perms:Set[TID]) {
    def +(prop:Property[OID,_]):PermSet = {
      if (AccessControl.hasPermission(prop, state, user, thingId))
        copy(perms = perms + prop.id)
      else
        this
    }
  }
  object PermSet {
    def apply(tid:OID, state:SpaceState, user:User):PermSet = PermSet(tid, state, user, Set.empty)
  }
  implicit def perms2Set(permSet:PermSet):Set[TID] = permSet.perms
  
  def spaceInfo(state:SpaceState, user:User):SpaceInfo = {
    val perms = 
      PermSet(state, state, user) + 
        Apps.CanManipulateAppsPerm +
        Apps.CanUseAsAppPerm +
        AccessControl.CanCreateProp
    
    SpaceInfo(
      state, 
      // TODO: NameUtils.toUrl() is inconsistent with SafeUrl: they handle spaces differently.
      // We need to fix this inconsistency!
      state.linkName.map(NameUtils.toUrl(_)), 
      state.unsafeDisplayName,
      state.owner.toThingId.toString,
      state.ownerHandle,
      state.apps.map(spaceInfo(_, user)),
      perms)
  }
  
  def spaceInfo(topt:Option[SpaceState], rc:RequestContext):Option[SpaceInfo] = {
    topt.map { t => spaceInfo(t, rc.requesterOrAnon) }
  }
  
  def spaceInfo(info:querki.spaces.messages.SpaceInfo):SpaceInfo = {
    val querki.spaces.messages.SpaceInfo(spaceId, linkName, display, ownerHandle) = info
    // TODO: we should probably add the Apps to the internal SpaceInfo, to get them here?
    SpaceInfo(TID(spaceId.toThingId), Some(linkName), display, "", ownerHandle, Seq.empty, Set.empty)
  }
  
  def identityInfo(identity:PublicIdentity):IdentityInfo = {
    IdentityInfo(AsOID(identity.id), identity.name, identity.handle)
  }
  
  def userInfo(uopt:Option[User]):Option[UserInfo] = {
    uopt.map { user =>
      // TODO: this will need adjusting when we have multiple Identities. The mainIdentity should come first:
      val identityInfos = user.identities.map { identity =>
        identityInfo(identity)
      }
      UserInfo(AsOID(user.id), identityInfos)
    }
  }
  
  def requestInfo(rc:RequestContext)(implicit state:SpaceState):RequestInfo = {
    if (AccessControl.canRead(state, rc.requesterOrAnon, state.id)) {
      RequestInfo(
        userInfo(rc.requester), 
        spaceInfo(Some(state), rc), 
        rc.isOwner,
        rc.requesterOrAnon.level)
    } else {
      // Signal that this person doesn't have access to the Space:
      RequestInfo(None, None, false, rc.requesterOrAnon.level, true)
    }
  }
  
  def rootRequestInfo(rc:RequestContext):RequestInfo = {
    RequestInfo(
      userInfo(rc.requester),
      None,
      false,
      rc.requesterOrAnon.level
    )
  }
  
  def propInfo(prop:AnyProp, rc:RequestContext)(implicit state:SpaceState):PropInfo = {
    val typeId = prop.pType match {
      case mt:ModelTypeBase => {
        if (prop.pType.ifSet(Basic.ExplicitProp))
          prop.pType.id
        else
          mt.basedOn
      }
      case _ => prop.pType.id
    }
    
    val isShadow = (prop.model != querki.core.MOIDs.UrPropOID)
    
    PropInfo(
      prop, 
      prop.linkName, 
      prop.displayName, 
      prop.getPropOpt(Core.AppliesToKindProp).map(_.rawList).getOrElse(Seq.empty),
      prop.cType,
      typeId,
      isShadow
      )
  }
  
  def propValInfo(t:Thing, rc:RequestContext)(implicit state:SpaceState):Future[Seq[PropValInfo]] = {
    def oneProp(prop:AnyProp, v:QValue):Future[PropValInfo] = {
      val prompt = futOpt(prop.getPropOpt(Editor.PromptProp).map(_.renderPlain))
      val renderedV =
        if (v.pType.isInstanceOf[querki.core.IsTextType]) {
          Future.successful(HtmlWikitext(s"<pre><code>${v.cv.map(v.pType.toUser(_)).mkString("\n")}</code></pre>"))
        } else {
          v.wikify(QLRequestContext(rc))
      }
      val tooltip = futOpt(prop.getPropOpt(Conventions.PropSummary).map(_.render(prop.thisAsContext(rc, state, ecology))))
          
      for {
        p <- prompt
        r <- renderedV
        t <- tooltip
      }
        yield PropValInfo(propInfo(prop, rc), p, r, t)
    }
    
    val infoOpts = for {
      prop <- t.localProps
      if (!prop.ifSet(Core.InternalProp))
    }
      yield t.getPropOpt(prop).map(pv => oneProp(prop, pv.v))
    Future.sequence(infoOpts.flatten.toSeq)
  }
}
