package akka

import akka.actor.Actor
import akka.actor.Actor.Receive

trait AroundReceiveOverrideHack extends Actor {
  override protected[akka] def aroundReceive(receive: Receive, msg: Any): Unit = {
    aroundReceiveInt(receive, msg)
    super.aroundReceive(receive, msg)
  }

  protected def aroundReceiveInt(receive: Receive, msg: Any): Unit
}
