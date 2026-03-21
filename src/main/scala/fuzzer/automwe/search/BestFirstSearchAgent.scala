package fuzzer.automwe.search

import fuzzer.core.graph.{DFOperator, Graph, Node}
import fuzzer.automwe.rules.MinimizationRule
import fuzzer.automwe.MinimizationOracle

import scala.collection.mutable
import fuzzer.automwe.GraphComplexity

/** A search agent for constructing a minimal error-reproducing example.
  * 
  * Produces a minimal (not necessarily minimum) result based on:
  * - A consistent cost function.
  * - A set of minimization rules.
  * - A correct oracle.
  * 
  */
object BestFirstSearchAgent {

  /** Run best-first search on the initial graph.
    *
    * @param initial the starting graph; must be valid
    * @param oracle  the oracle to check candidates against
    * @param rules   rules used to generate candidate subgraphs
    * @return the minimized graph
    */
  def minimize(
    initial: Graph[DFOperator],
    oracle:  MinimizationOracle,
    rules:   Seq[MinimizationRule]
  ): Graph[DFOperator] = {

    // define an implicit ordering to use PriorityQueue
    implicit val orderByCost: Ordering[Graph[DFOperator]] = 
      Ordering.by[Graph[DFOperator], GraphComplexity](oracle.cost(_)).reverse
    
    val frontier = mutable.PriorityQueue(initial)(orderByCost)
    val visited  = mutable.Set[Graph[DFOperator]]()
    var best     = initial

    while (frontier.nonEmpty) {
      val current = frontier.dequeue()

      // Skip nodes that have already been processed
      if (!visited.contains(current)) {
        visited += current

        println(s"Expanding candidate w/ cost ${oracle.cost(current)}")
        if (oracle.isValidCandidate(current)) {
          // Track cheapest seen so far
          if (oracle.cost(current) < oracle.cost(best)) best = current

          val children = expand(current, rules, oracle)
          val validChildren = children.filter(oracle.isValidCandidate(_))

          if (is_goal(current, oracle, validChildren)) {
            return current
          }

          // push new children onto frontier
          validChildren
            .filterNot(visited.contains)
            .foreach(frontier.enqueue(_))
          
        }

      }
    }

    // if not 1-minimal goal found, return next best thing
    best
  }

  /** Expand a graph, by applying a sequence of rules to it. 
   * 
   * Return a set of candidate graphs that can be reached by a
   * single rule application at any node, sorted by their cost
   * as defined by the oracle.
   * 
   * @param graph the graph to expand
   * @param the set of rules to apply
   * @param the cost-determining oracle
   * 
   * @return an array of candidate graphs, sorted by cost. 
   */
  private def expand(
    graph: Graph[DFOperator],
    rules: Seq[MinimizationRule],
    oracle: MinimizationOracle
  ): Seq[Graph[DFOperator]] = {

    rules.flatMap {rule =>
      rule.apply(graph)  
    }.sortBy(oracle.cost(_))
  }

  /** Determine if a valid state is a goal state.
   * 
   * A goal state has the following properties:
   * - It is a valid state.
   * - It is 1-minimal; it produces no valid candidates with a cheaper cost.
   * 
   * Here, we assume that the candidate graph is expanded before we can check whether it is a goal state,
   * so we accept the previously-calculated candidates as a parameter. 
   * 
   * @param graph the candidate to check
   * @param oracle cost-determining oracle
   * @param children expanded children of the graph, in sorted increasing order of cost
   * 
   */
  def is_goal(
    graph: Graph[DFOperator],
    oracle: MinimizationOracle,
    children: Seq[Graph[DFOperator]]
  ): Boolean = {
    children.isEmpty || (oracle.cost(graph) <= oracle.cost(children.head))
  }

}
