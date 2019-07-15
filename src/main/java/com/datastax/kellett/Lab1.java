package com.datastax.kellett;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.streaming.OutputMode;
import org.apache.spark.sql.streaming.StreamingQuery;
import org.apache.spark.sql.streaming.StreamingQueryException;

public class Lab1 {

    public static void main(String[] args) throws StreamingQueryException {

        Logger.getLogger("org.apache").setLevel(Level.WARN);
        Logger.getLogger("org.apache.spark.storage").setLevel(Level.ERROR);

        // Connect to Spark
        System.out.println("DataStax Spark Streaming Workshop Lab 1");
        SparkSession session = SparkSession.builder()
                //.master("local[*]")  // use for debugging if necessary
                .appName("Spark Structured Streaming Lab1") // any name you like --- displays in Spark Management UI
                .getOrCreate();

        // Read available incoming events into a data frame
        Dataset<Row> df = session.readStream()
                .format("kafka") // If any trouble resolving "kafka" try long name "org.apache.spark.sql.kafka010.KafkaSourceProvider"
                .option("kafka.bootstrap.servers", "node0:9092") // point to kafka instance
                .option("subscribe", "rating_modifiers") // kafka topic we want to subscribe
                .load();

        // Register a table with Spark - this will hold the incoming events from Kafka
        df.createOrReplaceTempView("rating_modifiers_incoming");

        // key, value, timestamp are the columns available to me
        // Note that you must cast value to a string in order to get readable results
        //
        // This rather ungainly SQL statement is a way to parse a CSV string into individual columns.
        // The innermost cast is necessary to make the kafka value field readable.
        // The next-outer substring_index goes to the delimiter just past the target field, and takes everything
        // to the left of it.
        // The outermost substring_index takes the intermediate result and plucks out the rightmost field,
        // which is my final target.
        Dataset<Row> results = session.sql(
                "select    substring_index( cast(value as string), ',', 1)                     as instrument_symbol, "
                        + "substring_index( substring_index( cast(value as string), ',', 2), ',', -1) as event_sequence_number, "
                        + "substring_index( substring_index( cast(value as string), ',', 3), ',', -1) as ethical_category, "
                        + "substring_index( substring_index( cast(value as string), ',', 4), ',', -1) as source_url, "
                        + "substring_index( substring_index( cast(value as string), ',', 5), ',', -1) as source_weight, "
                        + "substring_index( substring_index( cast(value as string), ',', 6), ',', -1) as source_sentiment "
                        + "from rating_modifiers_incoming");

        // Create a sink
        // See https://spark.apache.org/docs/latest/structured-streaming-programming-guide.html#output-sinks for standard sink types
        // Here we use DSE's Cassandra sink
        StreamingQuery query = results.writeStream().format("org.apache.spark.sql.cassandra")
                .outputMode(OutputMode.Append()) // values are Complete, Update, and Append - use Append for non-aggregation queries
                .option("keyspace", "streaming_workshop")
                .option("table", "ratings_modifiers_all")
                .option("checkpointLocation", "dsefs://node0:5598/checkpoint/lab1/") // enables query restart after a failure
                .start();

        // Writes to console... use for debugging if necessary
//        StreamingQuery query = results.writeStream().format("console")
//                .outputMode(OutputMode.Append()) // values are Complete, Update, and Append - use Append for non-aggregation queries
//                .option("truncate", false)
//                .option("numRows", 30)
//                .start();

        // Rinse and repeat
        query.awaitTermination();
    }
}
