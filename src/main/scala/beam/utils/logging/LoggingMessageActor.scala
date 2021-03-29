package beam.utils.logging

import akka.actor.{Actor, ActorContext, ActorRef}
import akka.actor.Actor.Receive
import beam.utils.logging.MessageLogger.BeamMessage

/**
  * @author Dmitry Openkov
  */
trait LoggingMessageActor extends Actor {
  def loggedReceive: Receive

  override def receive: Receive =
    if (context.system.settings.AddLoggingReceive) LoggingMessageReceive(loggedReceive)
    else loggedReceive

  def contextBecome(receive: Receive): Unit =
    if (context.system.settings.AddLoggingReceive) context.become(LoggingMessageReceive(receive))
    else context.become(receive)
}

trait LoggingMessagePublisher extends Actor {

  def publishMessage(msg: Any): Unit =
    if (context.system.settings.AddLoggingReceive) {
      context.system.eventStream.publish(BeamMessage(context.sender(), context.self, msg))
    }

  def publishMessageFromTo(msg: Any, sender: ActorRef, receiver: ActorRef): Unit =
    if (context.system.settings.AddLoggingReceive) {
      context.system.eventStream.publish(BeamMessage(sender, receiver, msg))
    }

}

class LoggingMessageReceive(r: Receive)(implicit context: ActorContext) extends Receive {

  def isDefinedAt(o: Any): Boolean = {
    val handled = r.isDefinedAt(o)
    val event = BeamMessage(context.sender(), context.self, o)
    context.system.eventStream.publish(event)
    handled
  }

  def apply(o: Any): Unit = r(o)
}

object LoggingMessageReceive {
  def apply(r: Receive)(implicit context: ActorContext): LoggingMessageReceive = new LoggingMessageReceive(r)
}
