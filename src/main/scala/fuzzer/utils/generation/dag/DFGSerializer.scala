/** Utilities for loading/writing generated DFG's to disk using a simple JSON format.

The goal of this is to allow for generated graphs to be saved along with the generated source 
code and oracle results for use post-campaign; e.g. for automatic minimization of an interesting
example.
 */ 

package fuzzer.utils.generation.dag

import play.api.libs.json.{JsObject, JsValue, JsString, JsNumber, JsNull, JsArray, Json}
import scala.util.matching.Regex
import fuzzer.core.graph.{DFOperator, Graph, Node}
import fuzzer.core.global.FuzzerConfig
import fuzzer.utils.json.JsonReader

object DFGSerializer {

  /** Serialize a generated DFG into JSON format. 
    * 
    * The resulting serialization should contain enough information
    * to reconstruct the generated source code the graph was based on.
    * 
    * @example the resulting serialization might look like: 
    * ... 
    * 
    * @param graph the graph to serialize
    * @param resultType the fuzzing result (e.g. 'MismatchException')
    * @param config the global config settings in use at the time of generation
    * @param preamble the generated preamble, including generated UDFs
    * 
    * @return the serialized graph.
    * 
    */
  def serialize(graph: Graph[DFOperator], resultType: String, config: FuzzerConfig, preamble: String): JsValue = {
    val metaData = Json.obj(
        "framework" -> config.targetAPI,
        "resultType" -> resultType,
        "specPath" -> config.specPath,
        "preamble" -> preamble,
        "config" -> Json.obj(
            "localTpcdsPath" -> config.localTpcdsPath,
            "seed" -> config.seed,
            "probUDFInsert" -> config.probUDFInsert,
            "maxStringLength" -> config.maxStringLength,
            "maxListLength" -> config.maxListLength
        )
    )

    val nodeArray = JsArray(
        graph.nodesMap.values.toSeq.sortBy(_.id.stripPrefix("node_").toInt).map { node =>
            val op = node.value
            val isSource = node.parents.isEmpty
            assert(op.name != null, s"Node ${node.id} has a null operator name. Fill operators before serializing!")

            val baseFields = Seq( // Fields shared by all nodes, regardless of their role.
                "id"       -> JsString(node.id),
                "operator" -> (if (op.name != null) JsString(op.name) else JsNull), 
                "role"     -> JsString( 
                    if (isSource)         "source" 
                    else if (node.isSink) "sink"
                    else                  "internal"
                )
            )

            val tableField = // table binding; source nodes only
                if (isSource && op.state != null)
                    Seq("table" -> JsString(op.state.originalIdentifier.stripSuffix('_' + node.id)))
                else
                    Seq.empty   

            val paramsField = // Node parameters
                if (op.params.nonEmpty)
                    Seq("params" -> JsObject(op.params.map { case (k, v) => k -> JsString(v) }))
                else 
                    Seq.empty

            JsObject(baseFields ++ tableField ++ paramsField)
        }
    )

    val edgeArray = JsArray( // Edges are derived from the children mappings, sorted by parent_id, and labeled from parent_id -> child_id.
        // graph.children.toSeq.sortBy(_._1.stripPrefix("node_").toInt).flatMap { case (fromId, toIds) =>
        graph.children.toSeq.sortBy(node => node._1).flatMap { case (fromId, toIds) =>     
            toIds.map { toId => 
                Json.obj("from" -> fromId, "to" -> toId)
            }
        }
    )
    
    Json.obj(
        "metadata" -> metaData,
        "nodes" -> nodeArray,
        "edges" -> edgeArray
    )
  }

    /** Deserialize a serialized graph into a Graph[DFOperator] object.
      * 
      * The deserialized object contains precisely enough information to reconstruct the 
      * corresponding source code. 
      * 
      * @param path the filepath to a serialized graph object (JSON file) 
      * @return the deserialized graph, the corresponding metadata, and a mapping of source nodes to table identifiers.
      */
    def deserialize(path: String): (Graph[DFOperator], JsObject, Map[String, String]) = {
        val pickle = JsonReader.readJsonFile(path)
        val metaData = (pickle \ "metadata").as[JsObject]

        // Step 1. Build nodes map; each node receives operator name and stored parameters.
        val nodesArray = (pickle \ "nodes").as[JsArray].value
        val nodesMap = 
            nodesArray.map { node => 
                val id = (node \ "id").as[String]
                val operator = (node \ "operator").as[String]
                val role = (node \ "role").as[String]
                val params = (node \ "params").asOpt[Map[String, String]].getOrElse(Map.empty)

                // The following makes assumptions about the naming conventions for nodes. 
                // Will this always hold...?
                val numericId = id.replaceAll("[^0-9]", "").toIntOption.getOrElse(0)
                val op = new DFOperator(operator, numericId)
                op.params = params

                id -> Node(id, op)
            }.toMap

        // Step 2. Parse table bindings for source nodes
        val sourceTableMap = 
            nodesArray.flatMap { n => 
                (n \ "table").asOpt[String].map { tableName => 
                    (n \ "id").as[String] -> tableName   
                }
            }.toMap


        // Step 3. Build children and parent maps from edge list.
        val edgeList = (pickle \ "edges").as[JsArray].value.map { e => 
            (e \ "from").as[String] -> (e \ "to").as[String]    
        }.toSeq

        val initMapping = 
            nodesMap.keys.map(_ -> List.empty[String]).toMap
            
        val children = 
            edgeList.foldLeft(initMapping) { case (acc, (from, to)) => 
                acc.updated(from, acc(from) :+ to)    
            }.toMap

        val parents = 
            edgeList.foldLeft(initMapping) { case (acc, (from, to)) => 
                acc.updated(to, acc(to) :+ from)    
            }.toMap

        // Step 4. Create graph and add graph reference to each node
        val graph = Graph(nodesMap, children, parents)
        graph.nodes.foreach(_.graph = graph)
        
        (graph, metaData, sourceTableMap)
    }
}
