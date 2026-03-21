package fuzzer.automwe

import fuzzer.utils.generation.dag.DFGSerializer
import fuzzer.core.global.FuzzerConfig
import fuzzer.framework.{UserImplDaskPython, UserImplFlinkPython, UserImplPolarsPython, UserImplSparkScala, UserImplTFPython}
import fuzzer.utils.json.JsonReader
import fuzzer.utils.random.Random
import fuzzer.factory.AdapterFactory
import fuzzer.data.tables.TableMetadata
import fuzzer.core.engine.FuzzerEngine

import play.api.libs.json.JsObject
import fuzzer.code.SourceCode

object DFGReconstructor {
  
    def main(args: Array[String]): Unit = {
        val source =
            reconstructSource(
                "target/dagfuzz-out/spark-scala/AnalysisException/g_0-a_0-dag_0-dfg1.dfg.json"
            )

        println("===== PREAMBLE =====")
        println(source.preamble)

        println("===== SOURCE =====")
        println(source.src)
    }

  /** Reconstruct source code from a serialized DFG file.
    * 
    * @param path the filepath to a serialized DFG JSON file.
    * @return reconstructed SourceCode object, passable directly to a CodeExecutor
    */
  def reconstructSource(path: String): SourceCode = {

    // Step 1. Deserialize the DFG
    val (graph, meta, sourceTableMap) = DFGSerializer.deserialize(path)

    val framework = (meta \ "framework").as[String]
    val specPath = (meta \ "specPath").as[String]

    // Step 2. Reconstruct config, spec, and components
    val baseConfig = framework match {
      case "spark-scala" => FuzzerConfig.getSparkScalaConfig
      case "flink-python" => FuzzerConfig.getFlinkPythonConfig
      case "dask-python" => FuzzerConfig.getDaskPythonConfig
      case "tensorflow-python" => FuzzerConfig.getTensorflowPythonConfig
      case "polars-python" => FuzzerConfig.getPolarsPythonConfig
      case _ => throw new RuntimeException(s"Unknown framework: $framework")
    }

    val savedConfig = (meta \ "config").as[JsObject]
    val config = baseConfig.copy(
        specPath = specPath,
        localTpcdsPath = (savedConfig \ "localTpcdsPath").as[String],
        probUDFInsert = (savedConfig \ "probUDFInsert").as[Double],
        maxStringLength = (savedConfig \ "maxStringLength").as[Int],
        maxListLength = (savedConfig \ "maxListLength").as[Int],
        seed = (savedConfig \ "seed").as[Int]
    )

    val spec = JsonReader.readJsonFile(config.specPath)

    val dag2CodeFunc = framework match {
      case "spark-scala" => UserImplSparkScala.dag2SparkScala(spec) _
      case "flink-python" => UserImplFlinkPython.dag2FlinkPython(spec) _
      case "dask-python" => UserImplDaskPython.dag2DaskPython(spec) _
      case "tensorflow-python" => UserImplTFPython.dag2tensorflowPython(spec) _
      case "polars-python" => UserImplPolarsPython.dag2polarsPython(spec) _
      case _ => throw new RuntimeException(s"Unknown framework: $framework")
    }

    // Step 3: Set up global state
    // dag2CodeFunc reads from State.config and State.iteration during codegen
    fuzzer.core.global.State.config = Some(config)
    fuzzer.core.global.State.iteration = 0
    Random.setSeed(config.seed)

    // Step 4: Boot execution environment and load tables
    val (dataAdapter, codeGenerator, codeExecutor) =
      AdapterFactory.createComponents(config, dag2CodeFunc)

    val terminateF = codeExecutor.setupEnvironment()
    dataAdapter.loadData(codeExecutor)
    val allTables = dataAdapter.getTables

    // Step 5: Resolve source table bindings
    // Match each source node's recorded table name to a loaded TableMetadata.
    // Sort order must match constructDFG's own sortBy(_.value.id).
    val selectedTables: Seq[TableMetadata] =
      graph.getSourceNodes
        .sortBy(_.value.id)
        .map { sourceNode =>
          val tableName = sourceTableMap.getOrElse(
            sourceNode.id,
            throw new RuntimeException(
              s"Source node '${sourceNode.id}' has no table binding in serialization"
            )
          )
          allTables.find(_.originalIdentifier == tableName)
            .getOrElse(throw new RuntimeException(
              s"Table '$tableName' not found in loaded data. " +
              s"Available: ${allTables.map(_.originalIdentifier).mkString(", ")}"
            ))
        }

    // Step 6: Bind tables, compute stateView, and generate code 
    val engine = new FuzzerEngine(
      config        = config,
      spec          = spec,
      dataAdapter   = dataAdapter,
      codeGenerator = codeGenerator,
      codeExecutor  = codeExecutor
    )

    val dfg = engine.constructDFG(graph, spec, selectedTables)
    val sourceCode = dfg.generateCode(dag2CodeFunc)
    val reconstruction = sourceCode.copy(preamble = (meta \ "preamble").as[String]) // This generates a new preamble just to overwrite it... surely a better way?

    // Step 7. Cleanup 
    codeExecutor.tearDownEnvironment(terminateF)

    reconstruction
  }
}
