package fuzzer.automwe.rules

import fuzzer.core.graph.{DFOperator, Graph, Node}

/** A single minimization rule that can be applied to a candidate graph.
  * 
  * Each rule determines the following:
  *     - when it can be applied (isApplicable)
  *     - a list of candidate graphs produced by applying the rule at each node (apply)
  *     - an estimate of how much complexity a rule application removes (costDelta)
  */
trait MinimizationRule {
    val name: String
    
    /** Determines if this rule can be applied to the given node.
      * 
      * @param node the node to check.
      * @return whether a rule application is possible.
      */
    def isApplicable(node: Node[DFOperator]): Boolean

    /** Apply the rule to a graph.
      * 
      * @param graph the graph to apply the rule to
      * @return a collection of candidate graphs
      */
    def apply(graph: Graph[DFOperator]): Seq[Graph[DFOperator]] = {
        graph.nodes
            .filter(node => isApplicable(node))
            .flatMap(node => applyAtNode(node))
    }

    /** Apply the rule to a given node.
      *
      * @param node
      * @return the resulting graph or None
      */
    protected def applyAtNode(node: Node[DFOperator]): Option[Graph[DFOperator]]

    /** Deep-copy a node.
      * 
      * @param node
      * @return new node with same params
      */
    protected def copyNode(node: Node[DFOperator]): Node[DFOperator] = {
        val node_op = node.value
        val copy_op = new DFOperator(node_op.name, node_op.id)
        copy_op.state = node_op.state
        copy_op.varName = node_op.varName
        copy_op.stateView = node_op.stateView
        copy_op.params = node_op.params
        new Node(node.id, copy_op)
    }


}
