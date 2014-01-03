package querki.ecology

import scala.reflect.runtime.{universe => ru}
import scala.reflect.runtime.universe._

import querki.values.SpaceState

class EcologyImpl extends Ecology with EcologyManager {
  
  // ******************************************************
  //
  // EcologyManager Implementation
  //
  
  val ecology:Ecology = this
  val runtimeMirror = ru.runtimeMirror(this.getClass().getClassLoader)
  
  def register(ecot:Ecot):Unit = {
    _registeredEcots = _registeredEcots + ecot
    
    ecot.implements.foreach { interfaceClass => 
      if (_registeredInterfaces.contains(interfaceClass)) {
        val currentRegistrant = _registeredInterfaces(interfaceClass)
        throw new AlreadyRegisteredInterfaceException(interfaceClass, currentRegistrant, ecot)
      } else {
        _registeredInterfaces = _registeredInterfaces + (interfaceClass -> ecot)
      }
    }
  }
  
  private def getType[T](clazz: Class[T]):Type = {
    val runtimeMirror = ru.runtimeMirror(clazz.getClassLoader)
    runtimeMirror.classSymbol(clazz).toType
  }
  
  def init(initialSpaceState:SpaceState):SpaceState = {
    initializeRemainingEcots(_registeredEcots, initialSpaceState)
  }
  
  def term():Unit = ???

  def isRegistered[C](implicit tag:TypeTag[C]):Boolean = {
    val clazz = runtimeMirror.runtimeClass(tag.tpe.typeSymbol.asClass)
    _registeredInterfaces.contains(clazz)
  }
  
  // ******************************************************
  //
  // Ecology Implementation
  //
  
  val manager:EcologyManager = this
  
  def api[T <: EcologyInterface : TypeTag]:T = {
    // This is a bit dubiously inefficient. But it is supposed to mainly be called via
    // InterfaceWrapper.get, which caches the result, so it shouldn't be called *too* often
    // after system initialization.
    val clazz = runtimeMirror.runtimeClass(typeOf[T].typeSymbol.asClass)
    _initializedInterfaces.get(clazz) match {
      case Some(ecot) => ecot.asInstanceOf[T]
      case None => {
        if (_registeredInterfaces.contains(clazz))
          throw new UninitializedInterfaceException(clazz)
        else
          throw new UnknownInterfaceException(clazz)
      }
    }
  }
  
  // ******************************************************
  //
  // Internals
  //
  
  /**
   * All of the Ecots that have been registered, in no particular order.
   */
  private var _registeredEcots:Set[Ecot] = Set.empty
  
  /**
   * All of the EcologyInterfaces that have been registered, and which Ecot implements each.
   */
  private var _registeredInterfaces:Map[Class[_], Ecot] = Map.empty
  
  /**
   * All of the Ecots that have been fully initialized.
   */
  private var _initializedEcots:Set[Ecot] = Set.empty
  
  /**
   * All of the EcologyInterfaces that have been fully initialized. Once they have been initialized, other systems may
   * access them.
   */
  private var _initializedInterfaces:Map[Class[_], Ecot] = Map.empty
  
  /**
   * Recursively initialize the system. In each recursive pass, go through the remaining Ecots, and initialize
   * the first one we find that has no uninitialized dependencies. If we get through a pass without being able to
   * initialize *anything*, we have failed.
   * 
   * One practical detail that is Querki-specific: as we go, we add each Ecot's Things to the SystemSpace.
   */
  private def initializeRemainingEcots(remaining:Set[Ecot], currentState:SpaceState):SpaceState = {
    if (remaining.isEmpty) {
      println("Ecology initialization complete")
      currentState
    } else {
      remaining.find(_.dependsUpon.forall(_initializedInterfaces.contains(_))) match {
        case Some(readyEcot) => {
          val newState = readyEcot.addSystemObjects(currentState)
          readyEcot.init
          _initializedEcots += readyEcot
          readyEcot.implements.foreach(interface =>_initializedInterfaces += (interface -> readyEcot))
          initializeRemainingEcots(remaining - readyEcot, newState)
        }
        // TODO: scan the remainder, and particularly their dependencies. If we find a dependency that
        // isn't in _registeredInterfaces, that means something isn't implemented yet. Otherwise, it
        // indicates a dependency loop. Include all of the remainder in an error message.
        case None => throw new Exception("Unable to initialize any more Ecots!")
      }
    }
  }
}