package querki.test.mid

import monocle.Lens
import monocle.macros.GenLens

import querki.data._

/**
 * The state of the world -- the Spaces and Things in them -- from the test harness' point of view.
 */
case class WorldState(spaces: Map[TID, SpaceTestState])

object WorldState {
  lazy val empty = WorldState(Map.empty)
  
  def updateCurrentSpace(f: SpaceTestState => SpaceTestState): TestOp[Unit] = TestOp.update { state =>
    require(state.client.spaceOpt.isDefined)
    val curSpaceId = state.client.spaceOpt.get.oid
    val fUpdatedSpaces = TestState.spacesL.modify { spaces =>
      val space = spaces(curSpaceId)
      val updated = f(space)
      spaces + (curSpaceId -> updated)          
    }
    fUpdatedSpaces(state)
  }
}

case class SpaceTestState(
  info: SpaceInfo,
  things: Map[TID, ThingTestState]
)

case class ThingTestState(info: ThingInfo)
