/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.serialization.jackson

import scala.concurrent.duration._
import java.time.Duration
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.Arrays

import akka.actor.ActorSystem
import akka.serialization.SerializationExtension
import akka.testkit.TestKit
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.Matchers
import org.scalatest.WordSpecLike
import java.util.Optional

import scala.concurrent.duration.FiniteDuration

import akka.actor.ActorRef
import akka.actor.Address
import akka.actor.ExtendedActorSystem
import akka.serialization.Serialization
import akka.testkit.TestActors
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.IntNode
import com.fasterxml.jackson.databind.node.ObjectNode

object ScalaTestMessages {
  final case class SimpleCommand(name: String)
  final case class SimpleCommand2(name: String, name2: String)
  final case class OptionCommand(maybe: Option[String])
  final case class BooleanCommand(published: Boolean)
  final case class TimeCommand(timestamp: LocalDateTime, duration: FiniteDuration)
  final case class CollectionsCommand(strings: List[String], objects: Vector[SimpleCommand])
  final case class CommandWithActorRef(name: String, replyTo: ActorRef)
  final case class CommandWithAddress(name: String, address: Address)

  final case class Event1(field1: String)
  final case class Event2(field1V2: String, field2: Int)

  final case class Zoo(first: Animal)
  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
  @JsonSubTypes(
    Array(
      new JsonSubTypes.Type(value = classOf[Lion], name = "lion"),
      new JsonSubTypes.Type(value = classOf[Elephant], name = "elephant")))
  sealed trait Animal
  final case class Lion(name: String) extends Animal
  final case class Elephant(name: String, age: Int) extends Animal
  // not defined in JsonSubTypes
  final case class Cockroach(name: String) extends Animal

}

class ScalaTestEventMigration extends JacksonMigration {
  override def currentVersion = 3

  override def transformClassName(fromVersion: Int, className: String): String =
    classOf[ScalaTestMessages.Event2].getName

  override def transform(fromVersion: Int, json: JsonNode): JsonNode = {
    val root = json.asInstanceOf[ObjectNode]
    root.set("field1V2", root.get("field1"))
    root.remove("field1")
    root.set("field2", IntNode.valueOf(17))
    root
  }
}

class JacksonJsonSerializerSpec extends JacksonSerializerSpec("jackson-json")
class JacksonCborSerializerSpec extends JacksonSerializerSpec("jackson-cbor")
class JacksonSmileSerializerSpec extends JacksonSerializerSpec("jackson-smile")

abstract class JacksonSerializerSpec(serializerName: String)
    extends TestKit(
      ActorSystem(
        "JacksonJsonSerializerSpec",
        ConfigFactory.parseString(s"""
    akka.serialization.jackson.migrations {
      "akka.serialization.jackson.JavaTestMessages$$Event1" = "akka.serialization.jackson.JavaTestEventMigration"
      "akka.serialization.jackson.JavaTestMessages$$Event2" = "akka.serialization.jackson.JavaTestEventMigration"
      "akka.serialization.jackson.ScalaTestMessages$$Event1" = "akka.serialization.jackson.ScalaTestEventMigration"
      "akka.serialization.jackson.ScalaTestMessages$$Event2" = "akka.serialization.jackson.ScalaTestEventMigration"
    }
    akka.actor {
      allow-java-serialization = off
      serialization-bindings {
        "java.lang.Object" = $serializerName
        "java.io.Serializable" = $serializerName
      }
    }
    """)))
    with WordSpecLike
    with Matchers
    with BeforeAndAfterAll {

  val serialization = SerializationExtension(system)

  override def afterAll {
    shutdown()
  }

  def checkSerialization(obj: AnyRef): Unit = {
    Serialization.withTransportInformation(system.asInstanceOf[ExtendedActorSystem]) { () =>
      val serializer = serializerFor(obj)
      val blob = serializer.toBinary(obj)
      println(s"# ${obj.getClass.getName}: ${new String(blob, "utf-8")}") // FIXME
      val deserialized = serializer.fromBinary(blob, serializer.manifest(obj))
      deserialized should ===(obj)
    }
  }

  def serializerFor(obj: AnyRef): JacksonSerializer =
    serialization.findSerializerFor(obj) match {
      case serializer: JacksonSerializer ⇒ serializer
      case s ⇒
        throw new IllegalStateException(s"Wrong serializer ${s.getClass} for ${obj.getClass}")
    }

  "JacksonSerializer with Java message classes" must {
    import JavaTestMessages._

    "serialize simple message with one constructor parameter" in {
      checkSerialization(new SimpleCommand("Bob"))
    }

    "serialize simple message with two constructor parameters" in {
      checkSerialization(new SimpleCommand2("Bob", "Alice"))
      checkSerialization(new SimpleCommand2("Bob", ""))
      checkSerialization(new SimpleCommand2("Bob", null))
    }

    "serialize message with boolean property" in {
      checkSerialization(new BooleanCommand(true))
      checkSerialization(new BooleanCommand(false))
    }

    "serialize message with Optional property" in {
      checkSerialization(new OptionalCommand(Optional.of("abc")))
      checkSerialization(new OptionalCommand(Optional.empty()))
    }

    "serialize message with collections" in {
      val strings = Arrays.asList("a", "b", "c")
      val objects = Arrays.asList(new SimpleCommand("a"), new SimpleCommand("2"))
      val msg = new CollectionsCommand(strings, objects)
      checkSerialization(msg)
    }

    "serialize message with time" in {
      val msg = new TimeCommand(LocalDateTime.now(), Duration.of(5, ChronoUnit.SECONDS))
      checkSerialization(msg)
    }

    "serialize with ActorRef" in {
      val echo = system.actorOf(TestActors.echoActorProps)
      checkSerialization(new CommandWithActorRef("echo", echo))
    }

    "serialize with Address" in {
      val address = Address("akka", "sys", "localhost", 2552)
      checkSerialization(new CommandWithAddress("echo", address))
    }

    "serialize with polymorphism" in {
      checkSerialization(new Zoo(new Lion("Simba")))
      checkSerialization(new Zoo(new Elephant("Elephant", 49)))
      // didn't expect Cockroach to be allowed when not defined in JsonSubTypes
      checkSerialization(new Cockroach("huh"))
    }

    "deserialize with migrations" in {
      val event1 = new Event1("a")
      val serializer = serializerFor(event1)
      val blob = serializer.toBinary(event1)
      val event2 = serializer.fromBinary(blob, classOf[Event1].getName).asInstanceOf[Event2]
      event1.getField1 should ===(event2.getField1V2)
      event2.getField2 should ===(17)
    }

    "deserialize with migrations from V2" in {
      val event1 = new Event1("a")
      val serializer = serializerFor(event1)
      val blob = serializer.toBinary(event1)
      val event2 = serializer.fromBinary(blob, classOf[Event1].getName + "#2").asInstanceOf[Event2]
      event1.getField1 should ===(event2.getField1V2)
      event2.getField2 should ===(17)
    }
  }

  "JacksonSerializer with Scala message classes" must {
    import ScalaTestMessages._

    "serialize simple message with one constructor parameter" in {
      checkSerialization(SimpleCommand("Bob"))
    }

    "serialize simple message with two constructor parameters" in {
      checkSerialization(SimpleCommand2("Bob", "Alice"))
      checkSerialization(SimpleCommand2("Bob", ""))
      checkSerialization(SimpleCommand2("Bob", null))
    }

    "serialize message with boolean property" in {
      checkSerialization(BooleanCommand(true))
      checkSerialization(BooleanCommand(false))
    }

    "serialize message with Optional property" in {
      checkSerialization(OptionCommand(Some("abc")))
      checkSerialization(OptionCommand(None))
    }

    "serialize message with collections" in {
      val strings = "a" :: "b" :: "c" :: Nil
      val objects = Vector(SimpleCommand("a"), SimpleCommand("2"))
      val msg = CollectionsCommand(strings, objects)
      checkSerialization(msg)
    }

    "serialize message with time" in {
      val msg = TimeCommand(LocalDateTime.now(), 5.seconds)
      checkSerialization(msg)
    }

    "serialize with ActorRef" in {
      val echo = system.actorOf(TestActors.echoActorProps)
      checkSerialization(CommandWithActorRef("echo", echo))
    }

    "serialize with Address" in {
      val address = Address("akka", "sys", "localhost", 2552)
      checkSerialization(CommandWithAddress("echo", address))
    }

    "serialize with polymorphism" in {
      checkSerialization(Zoo(Lion("Simba")))
      checkSerialization(Zoo(Elephant("Elephant", 49)))
      // didn't expect Cockroach to be allowed when not defined in JsonSubTypes
      checkSerialization(Cockroach("huh"))
    }

    "deserialize with migrations" in {
      val event1 = Event1("a")
      val serializer = serializerFor(event1)
      val blob = serializer.toBinary(event1)
      val event2 = serializer.fromBinary(blob, classOf[Event1].getName).asInstanceOf[Event2]
      event1.field1 should ===(event2.field1V2)
      event2.field2 should ===(17)
    }

    "deserialize with migrations from V2" in {
      val event1 = Event1("a")
      val serializer = serializerFor(event1)
      val blob = serializer.toBinary(event1)
      val event2 = serializer.fromBinary(blob, classOf[Event1].getName + "#2").asInstanceOf[Event2]
      event1.field1 should ===(event2.field1V2)
      event2.field2 should ===(17)
    }
  }
}
