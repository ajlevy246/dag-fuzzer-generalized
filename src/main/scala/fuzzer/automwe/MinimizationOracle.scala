package fuzzer.automwe

import fuzzer.core.graph.{DFOperator, Graph}
import fuzzer.core.engine.FuzzerEngine
import fuzzer.data.tables.TableMetadata
import fuzzer.core.interfaces.{CodeExecutor, DataAdapter}
import fuzzer.core.interfaces.CodeExecutor
import fuzzer.data.tables.TableMetadata
import fuzzer.utils.json.JsonReader
import fuzzer.code.SourceCode
import fuzzer.factory.AdapterFactory
import fuzzer.core.global.FuzzerConfig
import fuzzer.framework.{UserImplDaskPython, UserImplFlinkPython, UserImplPolarsPython, UserImplSparkScala, UserImplTFPython}

import play.api.libs.json.{JsValue, JsObject}
import fuzzer.utils.generation.dag.DFGSerializer
import fuzzer.adapters.spark.SparkCodeExecutor

/** The oracle is responsible for:
  * 1. Confirming that a candidate is valid, i.e. triggers the same exception as the original.
  * 2. Measuring the complexity of a candidate graph for ranking by a search agent.
  *
  * @param engine
  * @param spec
  * @param dag2CodeFunc
  * @param dataAdapter
  * @param codeExecutor
  * @param allTables
  * @param sourceTableMap
  * @param originalResult
  * @param preamble
  */
class MinimizationOracle(
    val engine: FuzzerEngine,
    val spec: JsValue,
    val dag2CodeFunc: Graph[DFOperator] => SourceCode,
    val dataAdapter: DataAdapter,
    val codeExecutor: CodeExecutor,
    val allTables: Seq[TableMetadata],
    val sourceTableMap: Map[String, String],
    val originalResult: String,
    val preamble: String // in the future, may introduce a rule that changes the preamble.
) {

    /** Returns true if a the candidate graph triggers the same exception as the original source.
      * 
      * @param candidate the candidate to check
      * @return True if candidate is valid
      */
    def isValidCandidate(candidate: Graph[DFOperator]): Boolean = {
        println(s"[ORACLE] Checking validity of candidate with cost: ${cost(candidate)}")
        try {
            val sourceCode = generateSource(candidate)
            val sparkExecutor = codeExecutor.asInstanceOf[SparkCodeExecutor]
            val (result, _, _) = sparkExecutor.checkOneGo(sourceCode)
            val resultType = result.getClass.toString.split('.').last

            val res = resultType == originalResult
            if (res) {
              println("\t- Candidate is valid.")
            } else {
              println(s"\t- Candidate is invalid: ${resultType}")
            }
            
            res
        } catch {
            case e: Exception =>
              println(s"\t- Exception during validation: ${e.getMessage}")
              e.printStackTrace()
              false
        }
    }

    /** Generate the source code from a candidate graph.
      * 
      * Injects saved preamble to overwrite the newly generated one.
      *
      * @param candidate the DFG to convert to code
      * @return the recovered source code
      */
    def generateSource(candidate: Graph[DFOperator]): SourceCode = {
        val selectedTables = resolveTableBindings(candidate)
        val dfg = engine.constructDFG(candidate, spec, selectedTables)
        val sourceCode = dfg.generateCode(dag2CodeFunc)
        sourceCode.copy(preamble = preamble)
    }

    def cost(candidate: Graph[DFOperator]): GraphComplexity = 
        GraphComplexity(
            nodeCount = candidate.nodes.length,
            sourceCount = candidate.getSourceNodes.length,
            edgeCount = candidate.children.values.map(_.length).sum
        )

    private def resolveTableBindings(graph: Graph[DFOperator]): Seq[TableMetadata] = {
        graph.getSourceNodes
        .sortBy(_.value.id)
        .map { sourceNode =>
            val tableName = sourceTableMap.getOrElse(
            sourceNode.id,
            throw new RuntimeException(
                s"No table binding for source node '${sourceNode.id}'. " +
                s"This node may have been introduced by a minimization rule " +
                s"that did not register a table binding."
            )
            )
            allTables.find(_.originalIdentifier == tableName)
            .getOrElse(throw new RuntimeException(
                s"Table '$tableName' not found in loaded data."
            ))
    }
  }
} 

object MinimizationOracle {
    /** Factory method: construct an oracle from a deserialized DFG file.
      * 
      * In charge of booting execution environment and maintaining it until shutdown.
      * 
      * @param dfgPath
      * @return
      */
  def fromDFGFile(dfgPath: String): (MinimizationOracle, Graph[DFOperator], () => Unit) = {
    val (graph, meta, sourceTableMap) = DFGSerializer.deserialize(dfgPath)

    val framework          = (meta \ "framework").as[String]
    val specPath           = (meta \ "specPath").as[String]
    val originalResultType = (meta \ "resultType").as[String]
    val storedPreamble     = (meta \ "preamble").as[String]

    val savedConfig = (meta \ "config").as[JsObject]
    val baseConfig  = frameworkToConfig(framework)
    val config      = baseConfig.copy(
      specPath        = specPath,
      localTpcdsPath  = (savedConfig \ "localTpcdsPath").as[String],
      probUDFInsert   = (savedConfig \ "probUDFInsert").as[Double],
      maxStringLength = (savedConfig \ "maxStringLength").as[Int],
      maxListLength   = (savedConfig \ "maxListLength").as[Int],
      seed            = (savedConfig \ "seed").as[Int]
    )

    val spec         = JsonReader.readJsonFile(config.specPath)
    val dag2CodeFunc = frameworkToCodeFunc(framework, spec)

    fuzzer.core.global.State.config    = Some(config)
    fuzzer.core.global.State.iteration = 0

    val (dataAdapter, codeGenerator, codeExecutor) =
      AdapterFactory.createComponents(config, dag2CodeFunc)

    // Boot once — kept alive for the entire minimization loop
    val terminateF = codeExecutor.setupEnvironment()
    dataAdapter.loadData(codeExecutor)
    val allTables = dataAdapter.getTables

    val engine = new FuzzerEngine(
      config        = config,
      spec          = spec,
      dataAdapter   = dataAdapter,
      codeGenerator = codeGenerator,
      codeExecutor  = codeExecutor
    )

    val oracle = new MinimizationOracle(
      engine             = engine,
      spec               = spec,
      dag2CodeFunc       = dag2CodeFunc,
      dataAdapter        = dataAdapter,
      codeExecutor       = codeExecutor,
      allTables          = allTables,
      sourceTableMap     = sourceTableMap,
      originalResult     = originalResultType,
      preamble           = storedPreamble
    )

    // Shutdown function — call after minimization is done
    val shutdown: () => Unit = () => codeExecutor.tearDownEnvironment(terminateF)

    (oracle, graph, shutdown)
  }

  private def frameworkToConfig(framework: String): FuzzerConfig = framework match {
    case "spark-scala"       => FuzzerConfig.getSparkScalaConfig
    case "flink-python"      => FuzzerConfig.getFlinkPythonConfig
    case "dask-python"       => FuzzerConfig.getDaskPythonConfig
    case "tensorflow-python" => FuzzerConfig.getTensorflowPythonConfig
    case "polars-python"     => FuzzerConfig.getPolarsPythonConfig
    case _ => throw new RuntimeException(s"Unknown framework: $framework")
  }

  private def frameworkToCodeFunc(
    framework: String,
    spec:      JsValue
  ): Graph[DFOperator] => SourceCode = framework match {
    case "spark-scala"       => UserImplSparkScala.dag2SparkScala(spec) _
    case "flink-python"      => UserImplFlinkPython.dag2FlinkPython(spec) _
    case "dask-python"       => UserImplDaskPython.dag2DaskPython(spec) _
    case "tensorflow-python" => UserImplTFPython.dag2tensorflowPython(spec) _
    case "polars-python"     => UserImplPolarsPython.dag2polarsPython(spec) _
    case _ => throw new RuntimeException(s"Unknown framework: $framework")
  }
}

/** Measure of a graph's complexity to rank minimization candidates
  *
  * @param nodeCount
  * @param sourceCount
  * @param edgeCount
  */
case class GraphComplexity(
    nodeCount: Int,
    sourceCount: Int,
    edgeCount: Int
) extends Ordered[GraphComplexity] {
    def compare(other: GraphComplexity): Int = {
        // weights: nodes > sources > edges
        val byNodes = this.nodeCount.compare(other.nodeCount)
        if (byNodes != 0) return byNodes

        val bySources = this.sourceCount.compare(other.sourceCount)
        if (bySources != 0) return bySources

        this.edgeCount.compare(other.edgeCount)
    }
}
