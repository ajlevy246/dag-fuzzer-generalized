/* Utilities for loading/writing generated DFG's to disk using a simple JSON format.

The goal of this is to allow for generated graphs to be saved along with the generated source 
code and oracle results for use post-campaign; e.g. for automatic minimization of an interesting
example.
 */ 

package fuzzer.utils.generation.dag

import play.api.libs.json.{JsObject, JsValue, JsString, JsNumber, JsNull, JsArray, Json}
import fuzzer.core.graph.{DFOperator, Graph, Node}

object DFGSerializer {
  def serialize(graph: Graph[DFOperator]): JsValue = {
    /* Serialize a generated DFG into a JSON format (JsValue)*/

    // Nodes capture id, operator name, and a table binding (for source nodes)
    val nodeArray = JsArray(
        graph.nodesMap.values.toSeq.sortBy(_.id).map { node =>
            val op = node.value
            val isSource = node.parents.isEmpty

            // Fields shared by all nodes, regardless of their role
            val baseFields = Seq(
                "id"       -> JsString(node.id), // node id
                "numId"    -> JsNumber(op.id), // numerical identifier for the specific DF operator
                "operator" -> (if (op.name != null) JsString(op.name) else JsNull), // name of the specific oeprator. May be null. 
                "role"     -> JsString( 
                    if (isSource)         "source" // Load data
                    else if (node.isSink) "sink" // Final result
                    else                  "internal" // Intermediate nodes
                )
            )

            // Only source nodes have a bound table (via `.state`)
            val tableField: Seq[(String, JsValue)] = 
                if (isSource && op.state != null)
                    Seq("table" -> JsString(op.state.originalIdentifier))
                else
                    Seq.empty

            JsObject(baseFields ++ tableField)
        }
    )

    // Edges are derived from the children mappings, sorted by the source,
    val edgeArray = JsArray(
        graph.children.toSeq.sortBy(_._1).flatMap { case (fromId, toIds) =>    
            toIds.map { toId => 
                Json.obj("from" -> fromId, "to" -> toId)
            }
        }
    )
    
    Json.obj(
        "nodes" -> nodeArray,
        "edges" -> edgeArray
    )
  }
}
