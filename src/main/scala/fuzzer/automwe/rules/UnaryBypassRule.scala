package fuzzer.automwe.rules

import fuzzer.core.graph.{DFOperator, Graph, Node}


/** Removes one node from a unary path and replaces it with its parent. 
  * 
  * Applies at any unary operator. The result of successful iterations 
  * of this rule should bypass all non-necessary operators.  
  */
object UnaryBypassRule extends MinimizationRule{
  val name = "Unary Bypass"

  def isApplicable(node: Node[DFOperator]): Boolean = 
    (node.getInDegree == 1)

  protected def applyAtNode(node: Node[DFOperator]): Option[Graph[DFOperator]] = {
    println(s"\t- Bypassing node ${node.id}")

    val graph = node.graph
    val replacementId = node.parents.head.id
    val replacement = copyNode(graph.nodesMap(replacementId)).copy(id = node.id)
    
    // Create new graph structures, remove bypassed node.
    // - Remove parent; copy nodes, update node.id => replacement
    // - Update parents of the new node to point to the updated replacement id.
    // The replacement node must assume the id of the bypassed node to avoid issues
    //   with previously generated params.
    // This assumes that the DFG is an inverted binary tree (each node has at most one child).
    val newNodesMap = graph.nodesMap
        .filterNot { case (nodeId, _) => nodeId == replacementId }
        .map { case (nodeId, node) => nodeId -> copyNode(node) }
        .updated(node.id, replacement)
    val newChildrenMap = graph.children
        .filterNot { case (nodeId, _) => nodeId == replacementId }
        .map { case (nodeId, children) => 
            if (graph.parents.getOrElse(replacementId, List.empty).contains(nodeId)) {
                nodeId -> List(node.id)
            } else {
                nodeId -> children
            }
        }
    val newParentsMap = graph.parents
        .filterNot { case (nodeId, _) => nodeId == replacementId}
        .updated(node.id, graph.parents(replacementId))

    // Create candidate, update graph references.
    val candidate = Graph(
        newNodesMap,
        newChildrenMap,
        newParentsMap
    )
    candidate.nodes.foreach(_.graph = candidate)

    assert(candidate.getSinkNodes.length == 1, "UnaryBypassRule produced a candidate with no sink...?")
    Some(candidate)
  }
}
