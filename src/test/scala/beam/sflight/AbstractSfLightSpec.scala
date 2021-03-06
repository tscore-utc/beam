package beam.sflight

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKitBase}
import beam.utils.SimRunnerForTest
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.{Config, ConfigFactory}
import org.matsim.api.core.v01.population.{Activity, Plan}
import org.scalatest._
import org.scalatest.wordspec.AnyWordSpecLike

import scala.collection.JavaConverters._
import scala.language.postfixOps

class AbstractSfLightSpec(val name: String)
    extends AnyWordSpecLike
    with TestKitBase
    with SimRunnerForTest
    with RouterForTest
    with ImplicitSender {
  lazy implicit val system: ActorSystem = ActorSystem(name, ConfigFactory.parseString("""
      |akka.test.timefactor=10""".stripMargin))

  def outputDirPath: String = basePath + "/" + testOutputDir + name
  def config: Config = testConfig("test/input/sf-light/sf-light.conf").resolve()

  def planToVec(plan: Plan): Vector[Activity] = {
    plan.getPlanElements.asScala
      .filter(_.isInstanceOf[Activity])
      .map(_.asInstanceOf[Activity])
      .toVector
  }
}
