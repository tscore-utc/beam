package beam.agentsim.infrastructure

import akka.actor.{Actor, ActorLogging, ActorRef}
import beam.agentsim.agents.modalbehaviors.DrivesVehicle._
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.infrastructure.power.{PowerController, SitePowerManager}
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.sim.config.BeamConfig
import beam.sim.{BeamScenario, BeamServices}
import beam.utils.DateUtils
import org.matsim.api.core.v01.Id

import scala.collection.concurrent.TrieMap

class ChargingNetworkManager(
  beamServices: BeamServices,
  beamScenario: BeamScenario,
  scheduler: ActorRef
) extends Actor
    with ActorLogging {
  import ChargingNetworkManager._

  private val beamConfig: BeamConfig = beamScenario.beamConfig
  private val vehiclesToCharge: TrieMap[Id[BeamVehicle], ChargingVehicle] = new TrieMap()
  private def vehicles: Map[Id[BeamVehicle], BeamVehicle] = vehiclesToCharge.mapValues(_.vehicle).toMap

  private val sitePowerManager = new SitePowerManager()
  private val powerController = new PowerController(beamServices, beamConfig)
  private val endOfSimulationTime: Int = DateUtils.getEndOfTime(beamConfig)

  private val gridConnectionEnabled = beamConfig.beam.agentsim.chargingNetworkManager.gridConnectionEnabled
  log.info("ChargingNetworkManager should be connected to grid: {}", gridConnectionEnabled)
  if (gridConnectionEnabled) {
    val isConnectionEstablished = powerController.initFederateConnection
    log.info("ChargingNetworkManager is actually connected to grid: {}", isConnectionEstablished)
  }

  override def receive: Receive = {
    case ChargingPlugRequest(vehicle, drivingAgent) =>
      log.info(
        "ChargingPlugRequest for vehicle {} by agent {} on stall {}",
        vehicle,
        drivingAgent.path.name,
        vehicle.stall
      )
      vehiclesToCharge.put(
        vehicle.id,
        ChargingVehicle(
          vehicle,
          drivingAgent,
          totalChargingSession = ChargingSession.Empty,
          lastChargingSession = ChargingSession.Empty
        )
      )

    case ChargingUnplugRequest(vehicle, tick) =>
      log.info("ChargingUnplugRequest for vehicle {} at {}", vehicle, tick)

      vehiclesToCharge
        .remove(vehicle.id)
        .map { cv =>
          val restChargeDuration = (tick % beamConfig.beam.cosim.helics.timeStep)
          val lastSession = cv.lastChargingSession
          val totalSession = cv.totalChargingSession

          val totalEnergyAdjustedByTick =
            if (lastSession.duration == 0) {
              // If the last session duration is 0 when ChargingUnplugRequest even happens -
              // it means the vehicle was added to the sequence of `vehiclesToCharge`
              // but a PlanningTimeOutTrigger event has never happened yet.
              // And this is the place when we need to
              // - calculate only the energy during `restChargeDuration`
              val (_, energy) = vehicle.refuelingSessionDurationAndEnergyInJoules(Some(restChargeDuration))
              energy
            } else {
              // If the last session duration is > 0 when ChargingUnplugRequest even happens -
              // it means one or more PlanningTimeOutTrigger events have happened already.
              // And this is the place when we need to:
              // - make and adjustment of last charging energy session using `restChargeDuration`
              // - apply this adjustment to all previous charging sessions
              val energyAdjustedByLastTick = Math.round(lastSession.energy / lastSession.duration * restChargeDuration)
              val energy = totalSession.energy - lastSession.energy + energyAdjustedByLastTick
              energy
            }
          log.debug(
            "Vehicle {} is removed from ChargingManager. Scheduling EndRefuelSessionTrigger at {} with {} J delivered",
            vehicle,
            tick,
            totalEnergyAdjustedByTick
          )
          scheduler ! ScheduleTrigger(
            EndRefuelSessionTrigger(tick, vehicle.getChargerConnectedTick(), totalEnergyAdjustedByTick, vehicle),
            cv.agent
          )
        }

    case TriggerWithId(PlanningTimeOutTrigger(tick), triggerId) =>
      if (vehiclesToCharge.nonEmpty)
        log.debug("PlanningTimeOutTrigger, tick: {}", tick)

      val (nextTick, requiredEnergyPerVehicle) = replanHorizon(tick)
      val maxChargeDuration = nextTick - tick

      val scheduleTriggers = requiredEnergyPerVehicle.flatMap {
        case (vehicleId, requiredEnergy) if requiredEnergy > 0 =>
          val ChargingVehicle(vehicle, agent, totalChargingSession, _) = vehiclesToCharge(vehicleId)

          log.debug("Charging vehicle {}. Required energy = {}", vehicle, requiredEnergy)

          val (chargingDuration, providedEnergy) =
            vehicle.refuelingSessionDurationAndEnergyInJoules(Some(maxChargeDuration))

          // make correctness if the vehicle is potentially over fueled
          if (vehicle.primaryFuelLevelInJoules + providedEnergy > vehicle.beamVehicleType.primaryFuelCapacityInJoule) {
            val adjustedProvidedEnergy = vehicle.beamVehicleType.primaryFuelCapacityInJoule - vehicle.primaryFuelLevelInJoules
            log.error(
              "Vehicle {} is over fueled! Provided {} energy, but required {} in J.",
              vehicle,
              providedEnergy,
              adjustedProvidedEnergy
            )
            vehicle.addFuel(adjustedProvidedEnergy)
          } else {
            vehicle.addFuel(providedEnergy)
          }

          val currentSession = ChargingSession(providedEnergy, chargingDuration)
          val totalSession = totalChargingSession.combine(currentSession)

          if (isChargingOver(vehicle, tick, maxChargeDuration, currentSession, totalSession)) {
            vehiclesToCharge.remove(vehicleId)
            Some(
              ScheduleTrigger(
                EndRefuelSessionTrigger(
                  tick + currentSession.duration.toInt,
                  vehicle.getChargerConnectedTick(),
                  totalSession.energy,
                  vehicle
                ),
                agent
              )
            )
          } else {
            vehiclesToCharge.update(vehicleId, ChargingVehicle(vehicle, agent, totalSession, currentSession))
            None
          }

        case (id, energy) if energy <= 0 =>
          log.warning(
            "Vehicle {}  (primaryFuelLevel = {}) requires energy {} - which is less or equals zero",
            vehicles(id),
            vehicles(id).primaryFuelLevelInJoules,
            energy
          )
          None
      }.toVector

      sender ! CompletionNotice(
        triggerId,
        if (tick < endOfSimulationTime)
          scheduleTriggers :+ ScheduleTrigger(PlanningTimeOutTrigger(nextTick), self)
        else {
          // if we still have a BEV/PHEV that is connected to a charging point,
          // we assume that they will charge until the end of the simulation and throwing events accordingly
          val completeTriggers = scheduleTriggers ++ vehiclesToCharge.map {
            case (_, cv) =>
              ScheduleTrigger(
                EndRefuelSessionTrigger(
                  tick,
                  cv.vehicle.getChargerConnectedTick(),
                  cv.totalChargingSession.energy,
                  cv.vehicle
                ),
                cv.agent
              )
          }
          vehiclesToCharge.clear()
          completeTriggers
        }
      )
  }

  private def replanHorizon(tick: Int): (Int, Map[Id[BeamVehicle], Double]) = {
    val requiredPower = sitePowerManager.getPowerOverPlanningHorizon(vehicles)

    val (bounds, nextTick) = if (gridConnectionEnabled) {
      powerController.publishPowerOverPlanningHorizon(requiredPower, tick)
      powerController.obtainPowerPhysicalBounds(tick)
    } else {
      powerController.defaultPowerPhysicalBounds(tick)
    }
    val requiredEnergyPerVehicle = sitePowerManager.replanHorizonAndGetChargingPlanPerVehicle(bounds, vehicles)

    if (requiredEnergyPerVehicle.nonEmpty)
      log.info("Required energy per vehicle: {}", requiredEnergyPerVehicle.mkString(","))

    (nextTick, requiredEnergyPerVehicle)
  }

  private def isChargingOver(
    vehicle: BeamVehicle,
    tick: Int,
    maxChargeDuration: Long,
    currentSession: ChargingSession,
    totalSession: ChargingSession
  ): Boolean = {
    if (vehicle.primaryFuelLevelInJoules == vehicle.beamVehicleType.primaryFuelCapacityInJoule) {
      log.debug(
        "Vehicle {} is fully charged. Scheduling EndRefuelSessionTrigger at {} with {} J delivered",
        vehicle.id,
        tick + currentSession.duration.toInt,
        totalSession.energy
      )
      true
    } else if (currentSession.duration < maxChargeDuration) {
      log.debug(
        "Vehicle {} is charged by a short time: {}. Scheduling EndRefuelSessionTrigger at {} with {} J delivered",
        vehicle.id,
        currentSession.duration,
        tick + currentSession.duration.toInt,
        totalSession.energy
      )
      true
    } else {
      log.debug(
        "Ending refuel cycle for vehicle {}. Provided {} J. during {}",
        vehicle.id,
        currentSession.energy,
        currentSession.duration
      )
      false
    }
  }

  override def postStop: Unit = {
    log.info("postStop")
    if (gridConnectionEnabled) {
      powerController.close()
    }
    super.postStop()
  }
}

object ChargingNetworkManager {
  final case class PlanningTimeOutTrigger(tick: Int) extends Trigger
  final case class ChargingSession(energy: Double, duration: Long) {

    def combine(other: ChargingSession): ChargingSession = ChargingSession(
      energy = this.energy + other.energy,
      duration = this.duration + other.duration
    )
  }
  final case class ChargingVehicle(
    vehicle: BeamVehicle,
    agent: ActorRef,
    totalChargingSession: ChargingSession,
    lastChargingSession: ChargingSession
  )

  object ChargingSession {
    val Empty: ChargingSession = ChargingSession(0.0, 0)
  }

}
