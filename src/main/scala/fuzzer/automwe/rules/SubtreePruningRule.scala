package fuzzer.automwe.rules

import fuzzer.core.graph.{DFOperator, Graph, Node}

/** Removes one input subtree of a binary node, replacing it with its sibling.
 * 
 * Given a binary node A with parents [L, R]:
 *  - Candidate 1: drop the L subtree and A, and replace with R
 *  - Candidate 2: drop the R subtree and A, and replace with L
 * 
 * Applies at any binary node.
 */
object SubtreePruningRule extends MinimizationRule{
  val name = "SubtreePruning"

  def isApplicable(node: Node[DFOperator]): Boolean =
      (node.getInDegree == 2)

  protected def applyAtNode(node: Node[DFOperator]): Option[Graph[DFOperator]] = None

  override def apply(graph: Graph[DFOperator]): Seq[Graph[DFOperator]] = {
    println("[RULE APPLICATION] Subtree Pruning")
    val res = graph.nodes
        .filter(node => isApplicable(node))
        .flatMap { binaryNode => 
            val left = binaryNode.parents.head
            val right = binaryNode.parents.last
            Seq(
                pruneSubtree(graph, binaryNode, drop=left, keep=right),
                pruneSubtree(graph, binaryNode, drop=right, keep=left)
            ).flatten
        }
    println(s"[RULE APPLICATION] Subtree Pruning Complete.\n\t- Returning ${res.length} candidates")
    res
  }

  /** Removes `binaryNode` and the `drop` subtree, wiring `keep` into
    * `binaryNode`'s children directly.
    *
    * Returns None if the resulting graph would be invalid (e.g. no sink).
    */
  private def pruneSubtree(
    graph:      Graph[DFOperator],
    binaryNode: Node[DFOperator],
    drop:       Node[DFOperator],
    keep:       Node[DFOperator]
  ): Option[Graph[DFOperator]] = {
    println(s"\t- Pruning node ${binaryNode.id}: keep ${keep.id}, drop: ${drop.id}")

    if (binaryNode.isSink) {
      println("[WARNING] Subtree pruning for sink nodes not yet implemented.")
      return None
    }

    // Find nodes that need to be removed; remove pruned nodes.
    val childrenOfBinaryNode = graph.children.get(binaryNode.id).getOrElse(List.empty)
    require(childrenOfBinaryNode.length == 1, "SubtreePruningRule failed. Expected an inverted binary tree...")
    val newChildId = childrenOfBinaryNode.head

    val nodeIdsToPrune = binaryNode.id +: getAncestorsIdsFrom(graph, drop)

    val newNodesMap = graph.nodesMap
      .filterNot { case (nodeId, _) => nodeIdsToPrune.contains(nodeId) }
      .view
      .mapValues(node => copyNode(node))
      .toMap
    val prunedChildren = graph.children
      .filterNot { case (nodeId, _) => nodeIdsToPrune.contains(nodeId) }
    val prunedParents = graph.parents
      .filterNot { case (nodeId, _) => nodeIdsToPrune.contains(nodeId) }
    
    // Update graph with new references for the updated child.
    // - Add `keep` as a parent of `newChild` node; remove `binaryNode`
    // - Add `newChild` as a child of `keep`; remove `binaryNode`
    val newParent = newNodesMap(keep.id)
    val newChild = newNodesMap(newChildId)

    //TODO: Verify that this solution works. Is it the best solution...?
    // - we clear the parameters of the child, to avoid situations where the child node 
    // - reference a pruned node directly in a parameter. 
    newChild.value.params = Map.empty[String, String]

    val updatedParents = prunedParents.updated(
      newChildId,
      prunedParents.get(newChildId).getOrElse(List.empty)
        .filterNot(_ == binaryNode.id) :+ keep.id
    )
    val updatedChildren = prunedChildren.updated(
      keep.id,
      prunedChildren.get(keep.id).getOrElse(List.empty)
        .filterNot(_ == binaryNode.id) :+ newChildId
    )

    // Construct new graph, update graph references at each node.
    val candidate = Graph(newNodesMap, updatedChildren, updatedParents)
    candidate.nodes.foreach(_.graph = candidate)
    
    if (candidate.getSinkNodes.length != 1) return None
    Some(candidate)
  }

  /** Return a list of node ids that are the ancestors of the given node, including the node itself.
    * 
    * This relies on the assumption that the graph is an inverted binary tree, and thus
    * no two nodes share the same parent. 
    *
    * @param graph
    * @param node
    */
  private def getAncestorsIdsFrom(graph: Graph[DFOperator], node: Node[DFOperator]): List[String] = {

    def traverseAncestorsFromId(nodeId: String): List[String] = {
      val parentIds = graph.parents.get(nodeId).getOrElse(List.empty)
      parentIds ++ parentIds.flatMap { parentId =>
        traverseAncestorsFromId(parentId)
      }
    }

    node.id +: traverseAncestorsFromId(node.id)
  }
  
}