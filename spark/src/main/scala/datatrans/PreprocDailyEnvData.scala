package datatrans

import datatrans.Utils._
import org.apache.hadoop.fs.{FileUtil, Path}
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
import org.joda.time._
import scopt._

import scala.util.matching.Regex

case class PreprocDailyEnvDataConfig(
                   input_directory : String = "",
                   output_prefix : String = ""
                 )

object PreprocDailyEnvData {
  def preproceEnvData(config : PreprocDailyEnvDataConfig, spark: SparkSession, filename : String) = {
    println("processing " + filename)
    val df = spark.read.format("csv").load(filename).toDF("a", "o3", "pmij")

    val aggregate = df.withColumn("start_date", to_date(to_timestamp(df("a")))).groupBy("start_date").agg(avg("o3").alias("o3_avg"), avg("pmij").alias("pmij_avg"), max("o3").alias("o3_max"), max("pmij").alias("pmij_max"))

    val hc = spark.sparkContext.hadoopConfiguration
    val name = new Path(filename).getName.split("[.]")(0)
    val output_dir = f"${config.output_prefix}${name}Daily"
    val output_dir_path = new Path(output_dir)
    val output_dir_fs = output_dir_path.getFileSystem(hc)

    val output_filename = f"${config.output_prefix}${name}Daily.csv"
    val output_file_path = new Path(output_filename)
    val output_file_fs = output_file_path.getFileSystem(hc)

    aggregate.write.csv(output_dir)

    FileUtil.copyMerge(output_dir_fs, output_dir_path, output_dir_fs, output_file_path, true, hc, null)
  }



  def main(args: Array[String]) {
    val parser = new OptionParser[PreprocDailyEnvDataConfig]("series_to_vector") {
      head("series_to_vector")
      opt[String]("input_directory").required.action((x, c) => c.copy(input_directory = x))
      opt[String]("output_prefix").required.action((x, c) => c.copy(output_prefix = x))
    }

    val spark = SparkSession.builder().appName("datatrans preproc").config("spark.sql.pivotMaxValues", 100000).config("spark.executor.memory", "16g").config("spark.driver.memory", "64g").getOrCreate()

    spark.sparkContext.setLogLevel("WARN")

    // For implicit conversions like converting RDDs to DataFrames
    import spark.implicits._

    parser.parse(args, PreprocDailyEnvDataConfig()) match {
      case Some(config) =>

        time {
          val hc = spark.sparkContext.hadoopConfiguration
          val input_dir_path = new Path(config.input_directory)
          val input_dir_fs = input_dir_path.getFileSystem(hc)

          val itr = input_dir_fs.listFiles(input_dir_path, false)
          while (itr.hasNext) {
            val file = itr.next()
            if (file.getPath.getName.endsWith(".csv")) {
              preproceEnvData(config, spark, file.getPath.toString)
            }
          }
        }
      case None =>
    }





    spark.stop()


  }
}