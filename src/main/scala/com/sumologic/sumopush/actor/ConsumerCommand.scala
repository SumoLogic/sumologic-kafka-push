package com.sumologic.sumopush.actor

sealed trait ConsumerCommand

object ConsumerCommand {
  case object ConsumerShutdown extends ConsumerCommand
}
