import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.tooling.Utils
import datadog.trace.bootstrap.autotrace.AutotraceGraph
import datadog.trace.bootstrap.autotrace.AutotraceNode
import datadog.trace.bootstrap.autotrace.GraphMutator

import java.util.concurrent.TimeUnit

class AutotraceGraphTest extends AgentTestRunner {
  def "unique edges only added once" () {
    setup:
    AutotraceGraph graph = new AutotraceGraph(Utils.getBootstrapProxy(), new NoOpMutator(), TimeUnit.NANOSECONDS.convert(10, TimeUnit.MILLISECONDS), TimeUnit.NANOSECONDS.convert(1, TimeUnit.MILLISECONDS))
    AutotraceNode node1 = graph.getNode(null, "Foo", "bar", true)
    AutotraceNode node2 = graph.getNode(null, "Foo", "baz", true)
    AutotraceNode node3 = graph.getNode(null, "Foo", "baz", true)
    node1.addEdges(node2, node3, node3, node2)
    node1.addEdges(node3)

    expect:
    node1.getEdges().size() == 1
  }

  def "no strong classloader refs held" () {
    // TODO
  }

  class NoOpMutator implements GraphMutator {

    @Override
    void updateBytecode(AutotraceNode node) {
      node.markBytecodeUpdated()
    }

    @Override
    void awaitUpdates() {
    }
  }
}
