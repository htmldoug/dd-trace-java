package datadog.trace.bootstrap.autotrace;

/**
 * Means of updating the state of an autotrace graph.
 *
 * TODO: rename?
 */
public interface GraphMutator {
  /**
   * Update bytecode instrumentation of a node to reflect the node's tracing and expansion states.
   *
   * <p>This request is not guaranteed to actually be processed. Implementation may chose to drop
   * request for performance reasons.
   */
  void updateBytecode(AutotraceNode node);

  /**
   * Block the current thread until all node updates have completed.
   *
   * Do not call this method under a class-loading lock as that may cause deadlocks.
   */
  void awaitUpdates();
}
