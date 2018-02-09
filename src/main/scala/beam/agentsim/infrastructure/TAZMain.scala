package beam.agentsim.infrastructure

import java.io.{File, FileReader, FileWriter}
import java.util
import java.util.ArrayList

import beam.agentsim.agents.PersonAgent
import beam.utils.scripts.HasXY.wgs2Utm
import beam.utils.scripts.PlansSampler._
import beam.utils.scripts.QuadTreeExtent
import com.vividsolutions.jts.geom.Geometry
import org.geotools.data.simple.SimpleFeatureIterator
import org.geotools.data.{FileDataStore, FileDataStoreFinder}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree
import org.matsim.core.utils.gis.ShapeFileReader
import org.matsim.core.utils.misc.Counter
import org.opengis.feature.simple.SimpleFeature
import util.HashMap

import beam.utils.ObjectAttributesUtils
import beam.utils.scripts.HouseholdAttrib.HousingType
import org.matsim.utils.objectattributes.{ObjectAttributes, ObjectAttributesXmlWriter}
import org.supercsv.cellprocessor.ParseDouble
import org.supercsv.cellprocessor.FmtBool
import org.supercsv.cellprocessor.FmtDate
import org.supercsv.cellprocessor.constraint.{LMinMax, NotNull, Unique, UniqueHashCode}
import org.supercsv.cellprocessor.ift.CellProcessor
import org.supercsv.exception.SuperCsvConstraintViolationException
import org.supercsv.util.CsvContext
import org.supercsv.io._
import org.supercsv.prefs.CsvPreference

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer


object TAZCreatorScript extends App {

  /*
  val shapeFile: String = "Y:\\tmp\\beam\\tl_2011_06_taz10\\tl_2011_06_taz10.shp";
  val taz=new TAZTreeMap(shapeFile, "TAZCE10")

// TODO: attriutes or xml from config file - allow specifying multiple files
    // create test attributes data for starting


  val tazParkingAttributesFilePath="Y:\\tmp\\beam\\infrastructure\\tazParkingAttributes.xml"

  val tazParkingAttributes: ObjectAttributes =new ObjectAttributes()

  tazParkingAttributes.putAttribute(Parking.PARKING_MANAGER, "className", "beam.agentsim.infrastructure.BayAreaParkingManager")

  for (tazVal:TAZ <-taz.tazQuadTree.values()){
    tazParkingAttributes.putAttribute(tazVal.tazId.toString, Parking.PARKING_TYPE, ParkingType.PARKING_WITH_CHARGER)
    tazParkingAttributes.putAttribute(tazVal.tazId.toString, Parking.PARKING_CAPACITY, 1.toString)
    tazParkingAttributes.putAttribute(tazVal.tazId.toString, Parking.HOURLY_RATE, 1.0.toString)
    tazParkingAttributes.putAttribute(tazVal.tazId.toString, Parking.CHARGING_LEVEL, ChargerLevel.L2)
  }

  //TODO: convert shape file to taz csv.
  // create script for that to use sometimes.
  //#TAZ params
  //  beam.agentsim.taz.file=""
  //#Parking params
  //  beam.agentsim.infrastructure.parking.attributesFilePaths=""




  ObjectAttributesUtils.writeObjectAttributesToCSV(tazParkingAttributes,tazParkingAttributesFilePath)

  println(shapeFile)

  println(taz.getId(-120.8043534,+35.5283106))
*/
  /**
  println("HELLO WORLD")
  val path = "C:\\Users\\Felipe\\Desktop\\Ori\\taz\\list_taz.csv"
  val mapTaz = TAZTreeMap.fromCsv(path)
  print(mapTaz)
    */
  //Test Write File
  if (null != args && 3 == args.size){
    println("Running conversion")
    val pathFileShape = args(0)
    val tazIdName = args(1)
    val destination = args(2)

    println("Process Started")
    TAZTreeMap.shapeFileToCsv(pathFileShape,tazIdName,destination)
    println("Process Terminate...")
  } else {
    println("Please specify: shapeFilePath tazIDFieldName destinationFilePath")
  }

}

class TAZTreeMap(tazQuadTree: QuadTree[TAZ]) {
  def getId(x: Double, y: Double): TAZ = {
    // TODO: is this enough precise, or we want to get the exact TAZ where the coordinate is located?
    tazQuadTree.getClosest(x,y)
  }
}

object TAZTreeMap {
  def fromShapeFile(shapeFilePath: String, tazIDFieldName: String): TAZTreeMap = {
    new TAZTreeMap(initQuadTreeFromShapeFile(shapeFilePath, tazIDFieldName))
  }

  private def initQuadTreeFromShapeFile(shapeFilePath: String, tazIDFieldName: String): QuadTree[TAZ] = {
    val shapeFileReader: ShapeFileReader = new ShapeFileReader
    shapeFileReader.readFileAndInitialize(shapeFilePath)
    val features: util.Collection[SimpleFeature] =     shapeFileReader.getFeatureSet
    val quadTreeBounds: QuadTreeBounds = quadTreeExtentFromShapeFile(features)

    val tazQuadTree: QuadTree[TAZ] = new QuadTree[TAZ](quadTreeBounds.minx, quadTreeBounds.miny, quadTreeBounds.maxx, quadTreeBounds.maxy)

    for (f <- features.asScala) {
      f.getDefaultGeometry match {
        case g: Geometry =>
          var taz = new TAZ(f.getAttribute(tazIDFieldName).asInstanceOf[String], new Coord(g.getCoordinate.x, g.getCoordinate.y))
          tazQuadTree.put(taz.coord.getX, taz.coord.getY, taz)
        case _ =>
      }
    }
    tazQuadTree
  }

  private def quadTreeExtentFromShapeFile(features: util.Collection[SimpleFeature]): QuadTreeBounds = {
    var minX: Double = Double.MaxValue
    var maxX: Double = Double.MinValue
    var minY: Double = Double.MaxValue
    var maxY: Double = Double.MinValue

    for (f <- features.asScala) {
      f.getDefaultGeometry match {
        case g: Geometry =>
          val ca = g.getEnvelope.getEnvelopeInternal
          //val ca = wgs2Utm(g.getEnvelope.getEnvelopeInternal)
          minX = Math.min(minX, ca.getMinX)
          minY = Math.min(minY, ca.getMinY)
          maxX = Math.max(maxX, ca.getMaxX)
          maxY = Math.max(maxY, ca.getMaxY)
        case _ =>
      }
    }
    QuadTreeBounds(minX, minY, maxX, maxY)
  }

  private def quadTreeExtentFromCsvFile(lines: Seq[CsvTaz]): QuadTreeBounds = {
    var minX: Double = Double.MaxValue
    var maxX: Double = Double.MinValue
    var minY: Double = Double.MaxValue
    var maxY: Double = Double.MinValue

    for (l <- lines) {
      minX = Math.min(minX, l.coordX)
      minY = Math.min(minY, l.coordY)
      maxX = Math.max(maxX, l.coordX)
      maxY = Math.max(maxY, l.coordY)
    }
    QuadTreeBounds(minX, minY, maxX, maxY)
  }

  def fromCsv(csvFile: String): TAZTreeMap = {

    val lines = readCsvFile(csvFile)
    val quadTreeBounds: QuadTreeBounds = quadTreeExtentFromCsvFile(lines)
    val tazQuadTree: QuadTree[TAZ] = new QuadTree[TAZ](quadTreeBounds.minx, quadTreeBounds.miny, quadTreeBounds.maxx, quadTreeBounds.maxy)

    for (l <- lines) {
      val taz = new TAZ(l.id, new Coord(l.coordX, l.coordY))
      tazQuadTree.put(taz.coord.getX, taz.coord.getY, taz)
    }

    new TAZTreeMap(tazQuadTree)

  }

  private def readCsvFile(filePath: String): Seq[CsvTaz] = {
    var mapReader: ICsvMapReader = null
    val res = ArrayBuffer[CsvTaz]()
    try{
      mapReader = new CsvMapReader(new FileReader(filePath), CsvPreference.STANDARD_PREFERENCE)
      val header = mapReader.getHeader(true)
      var flag = true
      var line: java.util.Map[String, String] = mapReader.read(header:_*)
      while(null != line){
        val id = line.get("taz")
        val coordX = line.get("coord-x")
        val coordY = line.get("coord-y")
        res.append(CsvTaz(id, coordX.toDouble, coordY.toDouble))
        line = mapReader.read(header:_*)
      }

    } finally{
      if(null != mapReader)
        mapReader.close()
    }
    res
  }

  def shapeFileToCsv(shapeFilePath: String, tazIDFieldName: String , writeDestinationPath: String): Unit = {
    val shapeFileReader: ShapeFileReader = new ShapeFileReader
    shapeFileReader.readFileAndInitialize(shapeFilePath)
    val features: util.Collection[SimpleFeature] = shapeFileReader.getFeatureSet

    var mapWriter: ICsvMapWriter   = null;
    try {

      mapWriter = new CsvMapWriter(new FileWriter(writeDestinationPath),
        CsvPreference.STANDARD_PREFERENCE);


      val processors = getProcessors
      val header = Array[String]("taz", "coord-x", "coord-y")
      mapWriter.writeHeader(header:_*)
      var duplicatedValues = false

      for (f <- features.asScala) {
        f.getDefaultGeometry match {
          case g: Geometry =>
            try {
              val taz = new HashMap[String, Object]();
              taz.put(header(0), f.getAttribute(tazIDFieldName).asInstanceOf[String])
              taz.put(header(1), g.getCoordinate.x.toString)
              taz.put(header(2), g.getCoordinate.y.toString)
              mapWriter.write(taz, header, processors)
            }
            catch {
              case e: SuperCsvConstraintViolationException => duplicatedValues = true
            }
          case _ =>
        }
      }
      if (duplicatedValues) {
        println("DUPLICATED TAZ VALUES")
        groupTaz(features,tazIDFieldName)
          .filter(i => i._2.length >1)foreach (x =>{ println ( "ID TAZ : "+x._1 + "------------------")
            x._2 foreach(x => println("\t -> Coordinate X -> " + x.coordX.toString + "\t Y -> " + x.coordY.toString))
        } )

      }
    }
    finally {
      if( mapWriter != null ) {
        mapWriter.close()
      }
    }
  }

  private def getProcessors: Array[CellProcessor]  = {
    Array[CellProcessor](
      new UniqueHashCode(), // Id (must be unique)
      new NotNull(), // Coord X
      new NotNull()) // Coord Y
  }

  def groupTaz(features: util.Collection[SimpleFeature], tazIDFieldName: String): Map[String, Array[CsvTaz]] = {
    val featuresArray = features.toArray(Array[SimpleFeature]())
    val csvSeq = featuresArray.map{ f =>
      f.getDefaultGeometry match {
        case g: Geometry =>
          Some(CsvTaz(f.getAttribute(tazIDFieldName).asInstanceOf[String], g.getCoordinate.x, g.getCoordinate.y))
        case _ => None
      }
    } filter(_.isDefined) map(_.get)

    csvSeq.groupBy(_.id)
  }
}

case class QuadTreeBounds(minx: Double, miny: Double, maxx: Double, maxy: Double)
case class CsvTaz(id: String, coordX: Double, coordY: Double)

class TAZ(val tazId: Id[TAZ],val coord: Coord){
  def this(tazIdString: String, coord: Coord) {
    this(Id.create(tazIdString,classOf[TAZ]),coord)
  }
}


