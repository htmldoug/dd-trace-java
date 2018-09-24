package datadog.trace.bootstrap.autotrace;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * A single method in the autotrace graph.
 *
 * <p>This class is stateful.
 *
 * <p>Trace state: If node is being traced
 *
 * <p>Expansion state: If the node's callees and type hierarchy have been computed.
 */
@Slf4j
public class AutotraceNode {
  private final WeakReference<ClassLoader> classloader;
  private final String className;
  private final String methodSignature;
  private final GraphMutator graphMutator;
  private final AtomicBoolean isExpanded = new AtomicBoolean(false);
  private final AtomicReference<TracingState> tracingState =
      new AtomicReference<>(TracingState.UNSET);
  private final AtomicBoolean bytecodeTracingApplied = new AtomicBoolean(false);
  private final List<AutotraceNode> edges = new CopyOnWriteArrayList<>();

  AutotraceNode(
      GraphMutator graphMutator,
      ClassLoader classloader,
      String className,
      String methodSignature) {
    if (classloader == null) {
      throw new IllegalStateException("classloader cannot be null");
    }
    this.graphMutator = graphMutator;
    this.classloader = new WeakReference<>(classloader);
    this.className = className;
    this.methodSignature = methodSignature;
  }

  public ClassLoader getClassLoader() {
    final ClassLoader loader = classloader.get();
    if (loader == null) {
      throw new IllegalStateException("Classloader for " + this + " is garbage collected.");
    }
    return loader;
  }

  public String getClassName() {
    return className;
  }

  public String getMethodTypeSignature() {
    return methodSignature;
  }

  @Override
  public String toString() {
    return "<" + classloader.get() + "> " + className + "." + methodSignature;
  }

  public void enableTracing(boolean allowTracing) {
    boolean updateBytecode;
    if (allowTracing) {
      updateBytecode = tracingState.compareAndSet(TracingState.UNSET, TracingState.TRACING_ENABLED);
    } else {
      // unset -> disabled transitions do not require bytecode changes
      tracingState.compareAndSet(TracingState.UNSET, TracingState.TRACING_DISABLED);
      updateBytecode =
          tracingState.compareAndSet(TracingState.TRACING_ENABLED, TracingState.TRACING_DISABLED);
    }

    if (updateBytecode || (!bytecodeTracingApplied.get())) {
      bytecodeTracingApplied.set(false);
      log.debug("{}: Tracing bytecode modification requested. State = {}", this, tracingState);
      graphMutator.updateTracingInstrumentation(
          this,
          allowTracing,
          new Runnable() {
            @Override
            public void run() {
              log.debug("{}: Tracing bytecode modification complete.", this);
              bytecodeTracingApplied.set(true);
            }
          });
    }
  }

  public boolean isTracingEnabled() {
    return tracingState.get() == TracingState.TRACING_ENABLED;
  }

  public boolean isExpanded() {
    return isExpanded.get();
  }

  public void expand() {
    if (!isExpanded.get()) {
      log.debug("{}: autotrace expansion requested", this);
      graphMutator.expand(
          this,
          new Runnable() {
            @Override
            public void run() {
              isExpanded.compareAndSet(false, true);
              log.debug("{}: autotrace expansion complete", this);
            }
          });
    }
  }

  public void addEdges(List<AutotraceNode> edges) {
    // TODO: use a hashmap to store edges
    for (AutotraceNode edgeToAdd : edges) {
      if (!this.edges.contains(edgeToAdd)) {
        this.edges.add(edgeToAdd);
      }
    }
  }

  public List<AutotraceNode> getEdges() {
    // TODO: callers can mutate
    return edges;
  }

  /** Determines if this node can be auto-traced. */
  private enum TracingState {
    /** In the graph. Will be traced if time exceeds trace threshold. */
    UNSET,
    /** In the graph and viable for tracing. */
    TRACING_ENABLED,
    /** In the graph but not viable for tracing. */
    TRACING_DISABLED
  }
}
