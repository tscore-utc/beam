package beam.side.route
import java.nio.file.Paths
import java.util.concurrent.Executors

import beam.side.route.model.{GHPaths, TripPath}
import beam.side.route.processing._
import beam.side.route.processing.data.{DataLoaderIO, DataWriterIO, PathComputeIO}
import beam.side.route.processing.request.{GHRequestCoreIO, GHRequestHttpIO}
import beam.side.route.processing.tract.{CencusTractDictionaryIO, ODComputeIO}
import cats.effect.Resource
import com.graphhopper.GraphHopper
import com.graphhopper.reader.dem.{ElevationProvider, MultiSourceElevationProvider}
import org.http4s.EntityDecoder
import org.http4s.client.Client
import org.http4s.client.blaze._
import zio._
import zio.interop.catz._

import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal
import scala.util.{Failure, Try}

case class ComputeConfig(
  cencusTrackPath: String = "",
  odPairsPath: Option[String] = None,
  ghHost: String = "",
  output: String = "output",
  osmPath: String = "",
  ghLocation: String = "",
  parallel: Int = 4,
  factor: Int = 16,
  cArgs: Map[String, String] = Map()
)

trait AppSetup {

  val parser = new scopt.OptionParser[ComputeConfig]("RouteCompute") {
    head("Census Tract Compute App", "version 1.0")

    opt[String]('c', "cencus")
      .required()
      .valueName("<census_path>")
      .action((s, c) => c.copy(cencusTrackPath = s))
      .validate(
        s =>
          Try(Paths.get(s).toFile).filter(_.exists()) match {
            case Failure(e) => failure(e.getMessage)
            case _          => success
        }
      )
      .text("Census Tract Median path")

    opt[String]("od")
      .valueName("<od_pairs>")
      .action((s, c) => c.copy(odPairsPath = Some(s)))
      .validate(
        s =>
          Try(Paths.get(s).toFile).filter(_.exists()) match {
            case Failure(e) => failure(e.getMessage)
            case _          => success
        }
      )
      .text("O/D pairs path")

    opt[String]('h', "host")
      .valueName("<gh_host>")
      .action((s, c) => c.copy(ghHost = s))
      .validate(
        s =>
          if (s.isEmpty) {
            failure("Empty host name")
          } else {
            success
        }
      )
      .text("GH host")

    opt[String]("osm")
      .valueName("<osm_file>")
      .action((s, c) => c.copy(osmPath = s))
      .validate(
        s =>
          Try(Paths.get(s).toFile).filter(_.exists()) match {
            case Failure(e) => failure(e.getMessage)
            case _          => success
        }
      )
      .text("OSM path")

    opt[String]('l', "loc")
      .valueName("<location_directory>")
      .action((s, c) => c.copy(ghLocation = s))
      .validate(
        s =>
          Try(Paths.get(s).toFile.getParentFile).filter(f => f.exists() && f.isDirectory) match {
            case Failure(e) => failure(e.getMessage)
            case _          => success
        }
      )
      .text("Location directory path")

    opt[String]('o', "output")
      .valueName("<output_file>")
      .action((s, c) => c.copy(output = s))
      .validate(
        s =>
          if (s.isEmpty) {
            failure("Empty output file")
          } else {
            success
        }
      )
      .text("Output file")

    opt[Int]('n', "pn")
      .valueName("<parallel_exec>")
      .action((s, c) => c.copy(parallel = s))
      .validate(p => if (p > 0) success else failure("Parallel is negative"))

    opt[Int]('f', "factor")
      .valueName("<factor_exec>")
      .action((s, c) => c.copy(factor = s))
      .validate(p => if (p > 0) success else failure("Factor is negative"))

    checkConfig { conf =>
      if (conf.ghHost.isEmpty && conf.osmPath.isEmpty) failure("host or osm should be passed")
      else success
    }
  }
}

object RoutesComputationApp extends CatsApp with AppSetup {

  import TripPath._
  import beam.side.route.model.GHPaths._
  import org.http4s.circe._
  import runtime._

  override def run(args: List[String]): ZIO[zio.ZEnv, Nothing, Int] = {

    type T[+A] = RIO[zio.ZEnv, A]
    implicit val httpClient
      : Resource[({ type T[A] = RIO[zio.ZEnv, A] })#T, Client[({ type T[A] = RIO[zio.ZEnv, A] })#T]] =
      BlazeClientBuilder[({ type T[A] = RIO[zio.ZEnv, A] })#T](
        ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(8))
      ).resource
    implicit val pathEncoder: EntityDecoder[({ type T[A] = RIO[zio.ZEnv, A] })#T, GHPaths] =
      jsonOf[({ type T[A] = RIO[zio.ZEnv, A] })#T, GHPaths]
    implicit val dataLoader: DataLoader[({ type T[A] = RIO[zio.ZEnv, A] })#T, Queue] = DataLoaderIO()
    implicit val cencusDictionary: CencusTractDictionary[({ type T[A] = RIO[zio.ZEnv, A] })#T, Queue] =
      CencusTractDictionaryIO()

    (for {
      config <- ZIO.fromOption(parser.parse(args, ComputeConfig()))

      promise <- CencusTractDictionary[({ type T[A] = RIO[zio.ZEnv, A] })#T, Queue].compose(config.cencusTrackPath)

      graphHopper = new GraphHopper()
        .forServer()
        .setGraphHopperLocation(config.ghLocation)
        .setDataReaderFile(config.osmPath)
        .setElevation(true)
        .setElevationProvider(new MultiSourceElevationProvider())
        .importOrLoad()
      ghRequest: GHRequest[({ type T[A] = RIO[zio.ZEnv, A] })#T] = GHRequestCoreIO(graphHopper)

      pathCompute: PathCompute[({ type T[A] = RIO[zio.ZEnv, A] })#T] = PathComputeIO(config.ghHost)

      odCompute: ODCompute[({ type T[A] = RIO[zio.ZEnv, A] })#T] = ODComputeIO(config.parallel, config.factor)

      tracts <- promise.await

      pathQueue <- ODCompute[({ type T[A] = RIO[zio.ZEnv, A] })#T](odCompute)
        .pairTrip(config.odPairsPath, tracts._2)(pathCompute, pathEncoder, ghRequest, dataLoader)

      dataWriter: DataWriter[({ type T[A] = RIO[zio.ZEnv, A] })#T, Queue] = DataWriterIO(config.parallel, config.factor)

      linesFork <- DataWriter[({ type T[A] = RIO[zio.ZEnv, A] })#T, Queue](dataWriter)
        .writeFile(Paths.get(config.output), pathQueue, tracts._1)
        .fork

      _ <- linesFork.join
    } yield config).fold(_ => -1, _ => 0)
  }
}