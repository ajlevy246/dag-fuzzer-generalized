This file serves as a place to take notes as I setup and contribute to DAGger.

## Setup & Running the Framework
### `requirements.txt`
> Python3.10 seems recommended. I needed to remove versions specifications from `requirements.txt` in order for pip installation to work, due to dependency conflicts. We'll see if this causes issues later. 

### `MainFuzzer.scala`
> Setting up the fuzzer logging directories is performed by the following lines:
> ```scala
> deleteDir(config.dagGenDir) // ["dag-gen/DAGs/DAGs"]
> deleteDir(config.outDir) // ["./target/dagfuzz-out/spark-scala"]
> createDir(config.outDir) // ["./target/dagfuzz-out/spark-scala"]
>```
> These utils are written in `src/main/fuzzer/utils/io/ReadWriteUtils.scala`, and use hardcoded unix commands (like `rm`) that don't work on my Windows system.
> I replaced it temporarily with:
> ```scala
> import org.apache.commons.io.FileUtils
> def deleteDir(path: String): Unit = {
>   val directory = new File(path)
>   if (directory.exists()) {
>       FileUtils.deleteDirectory(directory)
>   }
> }
> ```
> But then switched to working in linux with WSL. Keeping this change for now, however. 

### TPS-DC Data Files (`*.dat` -> `Parquet`)
> I get the following error when running:
> ```bash
> reading table call_center...
> Exception in thread "main" org.apache.spark.sql.AnalysisException: 
>     [PATH_NOT_FOUND] Path does not exist: file:/C:/Users/ajl64/IdeaProjects/dag-fuzzer-generalized/tpcds-data-5pc/call_center.
>```
> To regenerate the data, I'm using Databricks' TPC-DS generator on wsl with `dsdgen`:
> ```bash
> ~/tpcds-kit/tools$ ./dsdgen -scale 1 -dir /mnt/c/Users/ajl64/IdeaProjects/dag-fuzzer-generalized/tpcds-data-5pc
> ```
> Which generates .dat files. To convert to parquet I'm using PySpark via an AI generated script that has the schemas hardcoded. (I noticed after that these schemas are under `oracle-servers/tpcds-schema.json`).

### Running the Project
> To run the project with various frameworks, I'm adapting commands from the `cmds` log to fit my environment.

#### 1. Apache Spark
> Running in WSL with the following command:
> ```bash
> SPARK_LOCAL_IP=127.0.0.1                                           \
> spark-submit                                                       \
>     --driver-memory 8g                                            \
>     --conf spark.executor.memory=8g                                \
>     --class fuzzer.MainFuzzer                                      \
>     --master local[*]                                              \
>     target/scala-2.13/DAGFuzzerBetter-assembly-0.1.0-SNAPSHOT.jar  \
>     spark-scala
> ```
> The program seems to be running a loop by generating a query, loading data, registering the tables, executing the query, and recording the result. I'm not sure why the data needs to be read at each iteration, nor what the Exception classes are (in the `Result` line at each iteration). After a few iterations, the SparkContext stops with an error, and all further iterations fail with:
> ```bash
>DFG construction or codegen failed, attempt #1. Reason: java.lang.IllegalStateException: Cannot call methods on a stopped SparkContext.
>This stopped SparkContext was created at:
>...
>```

#### 2. PyFlink
> Since the PyFlink environment must run in a Python interpreter, the fuzzer (running in the JVM) interacts with it via a JSON 'oracle' server. First, I've downloaded `JaCoCo` to `lib/`. In one process, the oracle server is started first via:
> ```bash
> JAVA_TOOL_OPTIONS="-javaagent:./jacoco-0.8.14/lib/jacocoagent.jar=destfile=./jacoco-0.8.14/coverage/coverage-runtime.exec,append=false,dumponexit=true,output=tcpserver,port=6300" \
> ./.venv/bin/python \
> ./oracle-servers/pyflink-oracle-server/basic-json-server.py
> ```
> Next the fuzzer is run in parallel, pointed to the JSON server:
> ```bash
> java -cp "target/scala-2.13/CardinalityEstimatorTest-assembly-0.1.0-SNAPSHOT.jar:lib/*" \
> sqlsmith.FlinkFuzzTests \
> local[*] \
> --output-location target/fuzz-tests-output/flink-python/ \
> --duration 86400 \
> --no-hive \
> --tpcds-path tpcds-data/
> ```
> This command is failing, since no such sqlsmith.FlinkFuzzTests seems to have been included in the fat jar created via `sbt assembly`. 

#### 3. Dask
> Not yet tried.

#### 4. Polars
> Not yet tried.

## DFG Serialization
To aid in automatic minimization of interesting examples, we first need a method to save and load the graphs in question to disk as they are generated. I'm using the Play API to serialize DFG's into a JSON format. 

### Graph Format
The structure of a generated DFG is as a `Graph[DFOperator]` object. The `Graph` object stores a set of mappings for children, parents, and nodes themselves. Only the children and node mappings are necessary for reconstructing the entire graph, so only they will be serialized.

In addition, some data about each node must be preservered. Each `DFOperator` node has a `varName` that must be serialized, and source nodes holds a `state` attribute to a bound table name that must also be saved. 

Since `TableMetadata` may change during a test run, e.g. when `setIdentifier` is called to alias a table, I'm not sure what to serialize. For now, using `originalIdentifier`.

### Saving to disk
1. Updating `generateSingleProgram` to return not only the generated source code but also the generated DFG: `Graph[DFOperator]` in addition.
2. Updating `processSingleProgram` to write the DFG serialization in addition to the source code.

### Serialization Results
Initial result looks like:
```json
{
  "nodes" : [ {
    "id" : "node_0",
    "numId" : 0,
    "operator" : "select",
    "role" : "sink"
  }, {
    "id" : "node_1",
    "numId" : 1,
    "operator" : "join",
    "role" : "internal"
  }, {
    "id" : "node_10",
    "numId" : 4,
    "operator" : "groupBy",
    "role" : "internal"
  }, {
    "id" : "node_12",
    "numId" : 5,
    "operator" : "spark.table",
    "role" : "source",
    "table" : "web_site_node_12"
  }, ... ],
  "edges" : [ {
    "from" : "node_1",
    "to" : "node_0"
  }, {
    "from" : "node_10",
    "to" : "node_6"
  }, {
    "from" : "node_11",
    "to" : "node_6"
  }, {
    "from" : "node_12",
    "to" : "node_7"
  }, ... ]
}
```
> Questions: What is the aliasing for table names? Why do they reference specific nodes? Why does `numId` (sourced from `DFOperator.id`) reference the depth of the node?