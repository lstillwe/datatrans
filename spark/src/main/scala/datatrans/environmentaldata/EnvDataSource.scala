package datatrans.environmentaldata

import datatrans.GeoidFinder
import java.util.concurrent.atomic.AtomicInteger
import datatrans.Utils._
import org.apache.spark.sql.{DataFrame, SparkSession, Column}
import org.apache.spark.sql.types._
import play.api.libs.json._
import org.joda.time._
import play.api.libs.json.Json.JsValueWrapper

import org.apache.spark.sql.functions._
import org.apache.hadoop.fs._

case class EnvDataSourceConfig(
  patgeo_data : String = "",
  environmental_data : String = "",
  output_file : String = "",
  start_date : DateTime = DateTime.now(),
  end_date : DateTime = DateTime.now(),
  fips_data: String = "",
  indices : Seq[String] = Seq("o3", "pm25"),
  statistics : Seq[String] = Seq("avg", "max"),
  indices2 : Seq[String] = Seq("ozone_daily_8hour_maximum", "pm25_daily_average")
)


class EnvDataSource(spark: SparkSession, config: EnvDataSourceConfig) {
  val envSchema = StructType(
    StructField("start_date", DateType) ::
      (for(j <- Seq("avg", "max", "min", "stddev"); i <- Seq("o3", "pm25")) yield i + "_" + j).map(i => StructField(i, DoubleType)).toList
  )

  val FIPSSchema = StructType(
    List(
      StructField("Date", StringType),
      StructField("FIPS", StringType),
      StructField("Longitude", StringType),
      StructField("Latitude", StringType),
      StructField("pm25_daily_average", DoubleType),
      StructField("pm25_daily_average_stderr", DoubleType),
      StructField("ozone_daily_8hour_maximum", DoubleType),
      StructField("ozone_daily_8hour_maximum_stderr", DoubleType)
    )
  )

  def loadEnvDataFrame(input: (String, Seq[String], StructType)) : Option[DataFrame] = {
    val (filename, names, schema) = input
    val df = spark.read.format("csv").option("header", value = true).schema(schema).load(filename)
    if (names.forall(x => df.columns.contains(x))) {
      Some(df.cache())
    } else {
      print(f"$filename doesn't contain all required columns")
      None
    }
  }




  def loadFIPSDataFrame(input: (String, Seq[Int])) : Option[DataFrame] = {
    val (fips, years) = input
    val dfs2 = years.flatMap(year => {
      val filename = f"${config.environmental_data}/merged_cmaq_$year.csv"
      val df = inputCache((filename, config.indices2, FIPSSchema))
      df.map(df => df.filter(df.col("FIPS") === fips))
    })
    if(dfs2.nonEmpty) {
      val df2 = dfs2.reduce((a, b) => a.union(b))
      val df3 = df2.withColumn("start_date", to_date(df2.col("Date"),"yy/MM/dd"))
      Some(df3.cache())
    } else {
      None
    }
  }

  def loadRowColDataFrame(coors: Seq[(Int, (Int, Int))]) : Option[DataFrame] = {
    val dfs = coors.flatMap {
      case (year, (row, col)) =>
        val filename = f"${config.environmental_data}/cmaq$year/C$col%03dR$row%03dDaily.csv"
        inputCache((filename, names, envSchema))
    }
    if (dfs.nonEmpty) {
      Some(dfs.reduce((a, b) => a.union(b)).cache())
    } else {
      None
    }
  }

  val yearlyStatsToCompute : Seq[(String => Column, String)] = Seq((avg, "avg"), (max, "max"), (min, "min"), (stddev, "stddev"))

  def aggregateByYear(df: DataFrame, names: Seq[String]) = {
    val df2 = df.withColumn("year", year(df.col("start_date")))
    val exprs = for(name <- names; (func, suffix) <- yearlyStatsToCompute) yield func(name).alias(name + "_" + suffix)
    
    val aggregate = df2.groupBy("year").agg(
      exprs.head, exprs.tail : _*
    )
    df2.join(aggregate, Seq("year"))
  }

  def generateOutputDataFrame(key: (Seq[(Int, (Int, Int))], String, Seq[Int])) = {
    val (coors, fips, years) = key
    val df = inputCache3(coors)
    val df3 = inputCache2((fips, years))

    (df, df3) match {
      case (Some(df), Some(df3)) =>
        val dfyear = aggregateByYear(df, names)
        val df3year = aggregateByYear(df3.withColumn("year", year(df3.col("start_date"))), config.indices2)
        val names2 = names ++ config.indices2
        val names3 = for ((_, i) <- yearlyStatsToCompute; j <- names2) yield f"${j}_$i"

        Some(dfyear.join(df3year, Seq("start_date"), "outer").select("start_date",  names2 ++ names3: _*).cache())
      case _ =>
        None
    }
  }

  private val inputCache = new Cache(loadEnvDataFrame)
  private val inputCache2 = new Cache(loadFIPSDataFrame)
  private val inputCache3 = new Cache(loadRowColDataFrame)
  private val outputCache = new Cache(generateOutputDataFrame)
  val names = for (i <- config.statistics; j <- config.indices) yield f"${j}_$i"

  val geoidfinder = new GeoidFinder(config.fips_data, "")

  def run(): Unit = {

    time {
      import spark.implicits._

      val patient_dimension = config.patgeo_data
      println("loading patient_dimension from " + patient_dimension)
      val pddf0 = spark.read.format("csv").option("header", value = true).load(patient_dimension)

      val patl = pddf0.select("patient_num", "lat", "lon").map(r => (r.getString(0), r.getString(1).toDouble, r.getString(2).toDouble)).collect.toList

      val hc = spark.sparkContext.hadoopConfiguration

      val n = patl.size

      withCounter(count => 

      patl.par.foreach{
        case (r, lat, lon) =>
            println("processing patient " + count.incrementAndGet() + " / " + n + " " + r)
          val output_file = config.output_file.replace("%i", r)
          if(fileExists(hc, output_file)) {
            println(output_file + " exists")
          } else {
            val yearseq = (config.start_date.year.get to config.end_date.minusDays(1).year.get)
            val coors = yearseq.intersect(Seq(2010,2011)).flatMap(year => {
              latlon2rowcol(lat, lon, year) match {
                case Some((row, col)) =>
                  Seq((year, (row, col)))
                case _ =>
                  Seq()
              }
            })
            val fips = geoidfinder.getGeoidForLatLon(lat, lon)

            outputCache((coors, fips, yearseq)) match {
              case Some(df) =>
                writeDataframe(hc, output_file, df)
              case None =>
            }
          }

      })
    }
  }
}
