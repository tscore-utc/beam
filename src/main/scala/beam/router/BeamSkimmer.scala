package beam.router

import beam.agentsim.agents.choice.mode.DrivingCost
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.infrastructure.TAZTreeMap.TAZ
import beam.router.BeamRouter.Location
import beam.router.BeamSkimmer.Skim
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{
  BIKE,
  CAR,
  CAV,
  DRIVE_TRANSIT,
  RIDE_HAIL,
  RIDE_HAIL_POOLED,
  RIDE_HAIL_TRANSIT,
  TRANSIT,
  WALK,
  WALK_TRANSIT
}
import beam.router.model.{BeamLeg, BeamPath, EmbodiedBeamTrip}
import beam.sim.BeamServices
import beam.sim.common.GeoUtils
import beam.utils.FileUtils
import com.google.inject.{Inject, Provider}
import org.matsim.api.core.v01.Id
import org.matsim.core.controler.events.IterationEndsEvent
import org.matsim.core.controler.listener.IterationEndsListener

import scala.collection.concurrent.TrieMap

//TODO to be validated against google api
class BeamSkimmer @Inject()() extends IterationEndsListener {
  // The OD/Mode/Time Matrix
  var previousSkims: TrieMap[(Int, BeamMode, Id[TAZ], Id[TAZ]), Skim] = TrieMap()
  var skims: TrieMap[(Int, BeamMode, Id[TAZ], Id[TAZ]), Skim] = TrieMap()
  var previousModalAverage: TrieMap[BeamMode, Skim] = TrieMap()
  var modalAverage: TrieMap[BeamMode, Skim] = TrieMap()

  // 22.2 mph (9.924288 meter per second), is the average speed in cities
  //TODO better estimate can be drawn from city size
  // source: https://www.mitpressjournals.org/doi/abs/10.1162/rest_a_00744
  private val carSpeedMeterPerSec: Double = 9.924288
  // 12.1 mph (5.409184 meter per second), is average bus speed
  // source: https://www.apta.com/resources/statistics/Documents/FactBook/2017-APTA-Fact-Book.pdf
  // assuming for now that it includes the headway
  private val transitSpeedMeterPerSec: Double = 5.409184

  private val bicycleSpeedMeterPerSec: Double = 3

  // 3.1 mph -> 1.38 meter per second
  private val walkSpeedMeterPerSec: Double = 1.38

  // 940.6 Traffic Signal Spacing, Minor is 1,320 ft => 402.336 meters
  private val trafficSignalSpacing: Double = 402.336

  // average waiting time at an intersection is 17.25 seconds
  // source: https://pumas.nasa.gov/files/01_06_00_1.pdf
  private val waitingTimeAtAnIntersection: Double = 17.25

  def getTimeDistanceAndCost(
    origin: Location,
    destination: Location,
    departureTime: Int,
    mode: BeamMode,
    vehicleTypeId: org.matsim.api.core.v01.Id[BeamVehicleType],
    beamServicesOpt: Option[BeamServices] = None
  ): Skim = {
    beamServicesOpt match {
      case Some(beamServices) =>
        val origTaz = beamServices.tazTreeMap.getTAZ(origin.getX, origin.getY).tazId
        val destTaz = beamServices.tazTreeMap.getTAZ(origin.getX, origin.getY).tazId
        getSkimValue(departureTime, mode, origTaz, destTaz) match {
          case Some(skimValue) =>
            skimValue
          case None =>
            val (travelDistance, travelTime) = distanceAndTime(mode, origin, destination)
            val travelCost: Double = mode match {
              case CAR | CAV =>
                DrivingCost.estimateDrivingCost(
                  new BeamLeg(
                    departureTime,
                    mode,
                    travelTime,
                    new BeamPath(null, null, None, null, null, travelDistance)
                  ),
                  vehicleTypeId,
                  beamServices
                )
              case RIDE_HAIL =>
                beamServices.beamConfig.beam.agentsim.agents.rideHail.defaultCostPerMile * travelDistance / 1609.0 + beamServices.beamConfig.beam.agentsim.agents.rideHail.defaultCostPerMinute * travelTime / 60.0
              case RIDE_HAIL_POOLED =>
                0.6 * (beamServices.beamConfig.beam.agentsim.agents.rideHail.defaultCostPerMile * travelDistance / 1609.0 + beamServices.beamConfig.beam.agentsim.agents.rideHail.defaultCostPerMinute * travelTime / 60.0)
              case TRANSIT | WALK_TRANSIT | DRIVE_TRANSIT | RIDE_HAIL_TRANSIT => 0.25 * travelDistance / 1609
              case _                                                          => 0.0
            }
            Skim(travelTime, travelDistance, travelCost, 0)
        }
      case None =>
        val (travelDistance, travelTime) = distanceAndTime(mode, origin, destination)
        Skim(travelTime, travelDistance, 0.0, 0)
    }
  }

  def getRideHailPoolingTimeAndCostRatios(
                                           origin: Location,
                                           destination: Location,
                                           departureTime: Int,
                                           vehicleTypeId: org.matsim.api.core.v01.Id[BeamVehicleType],
                                           beamServices: BeamServices): (Double,Double) = {
        val origTaz = beamServices.tazTreeMap.getTAZ(origin.getX, origin.getY).tazId
        val destTaz = beamServices.tazTreeMap.getTAZ(origin.getX, origin.getY).tazId
    val solo = getSkimValue(departureTime, RIDE_HAIL, origTaz, destTaz) match {
      case Some(skimValue) if skimValue.count > 5 =>
        skimValue
      case _ =>
        modalAverage.get(RIDE_HAIL) match {
          case Some(skim) =>
            skim
          case None =>
            Skim(1.0, 0, 1.0,0)
        }
    }
        val pooled = getSkimValue(departureTime, RIDE_HAIL_POOLED, origTaz, destTaz) match {
          case Some(skimValue) if skimValue.count > 5 =>
            skimValue
          case _ =>
             modalAverage.get(RIDE_HAIL_POOLED) match {
              case Some(skim) =>
                skim
              case None =>
                Skim(1.1, 0, 0.6,0)
             }
        }
    (pooled.time/solo.time,pooled.cost/solo.cost)
  }

  private def distanceAndTime(mode: BeamMode, origin: Location, destination: Location) = {
    val speed = mode match {
      case CAR | CAV | RIDE_HAIL                                      => carSpeedMeterPerSec
      case RIDE_HAIL_POOLED                                           => carSpeedMeterPerSec * 1.1
      case TRANSIT | WALK_TRANSIT | DRIVE_TRANSIT | RIDE_HAIL_TRANSIT => transitSpeedMeterPerSec
      case BIKE                                                       => bicycleSpeedMeterPerSec
      case _                                                          => walkSpeedMeterPerSec
    }
    val travelDistance: Int = Math.ceil(GeoUtils.minkowskiDistFormula(origin, destination)).toInt
    val travelTime: Int = Math
      .ceil(travelDistance / speed)
      .toInt + ((travelDistance / trafficSignalSpacing).toInt * waitingTimeAtAnIntersection).toInt
    (travelDistance, travelTime)
  }

  private def getSkimValue(time: Int, mode: BeamMode, orig: Id[TAZ], dest: Id[TAZ]): Option[Skim] = {
    skims.get((timeToBin(time), mode, orig, dest)) match {
      case Some(skim) =>
        Some(skim)
      case None =>
        previousSkims.get((timeToBin(time), mode, orig, dest))
    }
  }

  def observeTrip(trip: EmbodiedBeamTrip, beamServices: BeamServices) = {
    val mode = trip.tripClassifier
    val correctedTrip = mode match {
      case WALK =>
        trip.beamLegs()
      case RIDE_HAIL =>
        trip.beamLegs().drop(1).dropRight(1)
      case _ =>
        trip.beamLegs().drop(1).dropRight(1)
    }
    val origLeg = correctedTrip.head
    val origCoord = beamServices.geo.wgs2Utm(origLeg.travelPath.startPoint.loc)
    val origTaz = beamServices.tazTreeMap
      .getTAZ(origCoord.getX, origCoord.getY)
      .tazId
    val destLeg = correctedTrip.last
    val destCoord = beamServices.geo.wgs2Utm(destLeg.travelPath.endPoint.loc)
    val destTaz = beamServices.tazTreeMap
      .getTAZ(destCoord.getX, destCoord.getY)
      .tazId
    val timeBin = timeToBin(origLeg.startTime)
    val key = (timeBin, mode, origTaz, destTaz)
    val payload =
      Skim(trip.totalTravelTimeInSecs.toDouble, trip.beamLegs().map(_.travelPath.distanceInM).sum, trip.costEstimate, 1)
    skims.get(key) match {
      case Some(existingSkim) =>
        val newPayload = Skim(
          mergeAverage(existingSkim.time, existingSkim.count, payload.time),
          mergeAverage(existingSkim.distance, existingSkim.count, payload.distance),
          mergeAverage(existingSkim.cost, existingSkim.count, payload.cost),
          existingSkim.count + 1
        )
        skims.put(key, newPayload)
      case None =>
        skims.put(key, payload)
    }
  }

  def timeToBin(departTime: Int) = {
    Math.floorMod(Math.floor(departTime.toDouble / 3600.0).toInt, 24)
  }

  def mergeAverage(existingAverage: Double, existingCount: Int, newValue: Double) =
    ((existingAverage * existingCount + newValue) / (existingCount + 1))

  override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
    val fileHeader = "hour,mode,origTaz,destTaz,travelTimeInS,cost,distanceInM,numObservations"
    // Output file relative path
    val filePath = event.getServices.getControlerIO.getIterationFilename(
      event.getServices.getIterationNumber,
      BeamSkimmer.outputFileBaseName + ".csv.gz"
    )
    //write the data to an output file
    FileUtils.writeToFile(
      filePath,
      Some(fileHeader),
      skims
        .map { keyVal =>
          s"${keyVal._1._1},${keyVal._1._2},${keyVal._1._3},${keyVal._1._4},${keyVal._2.time},${keyVal._2.cost},${keyVal._2.distance},${keyVal._2.count}"
        }
        .mkString("\n"),
      None
    )
    previousSkims = skims
    skims = new TrieMap()
  }
}

object BeamSkimmer {
  val outputFileBaseName = "skims"

  case class Skim(time: Double, distance: Double, cost: Double, count: Int)
}

//val householdBeamPlans = household.members.map(person => BeamPlan(person.getSelectedPlan)).toList
//val householdMatsimPlans = household.members.map(person => (person.getId -> person.getSelectedPlan)).toMap
//val fastSpeed = 50.0 * 1000.0 / 3600.0
//val medSpeed = 50.0 * 1000.0 / 3600.0
//val slowSpeed = 50.0 * 1000.0 / 3600.0
//val walkSpeed = 50.0 * 1000.0 / 3600.0
//val skim: Map[BeamMode, Double] = Map(
//CAV               -> fastSpeed,
//CAR               -> fastSpeed,
//WALK              -> walkSpeed,
//BIKE              -> slowSpeed,
//WALK_TRANSIT      -> medSpeed,
//DRIVE_TRANSIT     -> medSpeed,
//RIDE_HAIL         -> fastSpeed,
//RIDE_HAIL_POOLED  -> fastSpeed,
//RIDE_HAIL_TRANSIT -> medSpeed,
//TRANSIT           -> medSpeed
//)
