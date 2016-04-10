package com.github.levkhomich.akka.tracing.play

import akka.actor.ActorSystem

trait ActorSystemProvider {
  implicit def actorSystem: ActorSystem
}
