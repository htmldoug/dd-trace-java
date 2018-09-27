package datadog.trace.bootstrap.autotrace;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
  // ASM Unavailable on bootstrap
  private static final int ACC_PUBLIC = 0x0001;
  private static final int ACC_PRIVATE = 0x0002;
  private static final int ACC_PROTECTED = 0x0004;
  private static final int ACC_STATIC = 0x0008;
  private static final int ACC_FINAL = 0x0010;
  private static final int ACC_NATIVE = 0x0100;

  private static final String[] skipNamespaces = {"java", "scala", "groovy", "kotlin", "clojure"};

  private final WeakReference<ClassLoader> classloader;
  private final String className;
  private final String methodSignature;
  private final GraphMutator graphMutator;

  private final AtomicBoolean isExpanded = new AtomicBoolean(false);
  private final AtomicReference<TracingState> tracingState =
      new AtomicReference<>(TracingState.UNSET);

  private final Set<AutotraceNode> edges =
      Collections.newSetFromMap(new ConcurrentHashMap<AutotraceNode, Boolean>());
  // TODO: With default interface impls there may be more than one place to check for super bytecode
  // When implementation comes from a superclass
  private final AtomicReference<AutotraceNode> superNode = new AtomicReference<>();
  private final Set<AutotraceNode> implNodes =
      Collections.newSetFromMap(new ConcurrentHashMap<AutotraceNode, Boolean>());

  // TODO
  final int accessFlags = 0;

  private final AtomicInteger stateCount = new AtomicInteger(0);
  private final AtomicInteger lastUpdateStateCount = new AtomicInteger(0);

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
    for (final String skipPrefix : skipNamespaces) {
      if (this.className.startsWith(skipPrefix)) {
        log.debug("Skipping autotrace for core class {}", this);
        isExpanded.set(true);
        tracingState.set(TracingState.TRACING_DISABLED);
      }
    }
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

  public boolean isPrivate() {
    return (accessFlags & ACC_PRIVATE) != 0;
  }

  public boolean isStatic() {
    return (accessFlags & ACC_STATIC) != 0;
  }

  @Override
  public String toString() {
    return "<"
        + classloader.get().getClass().getName()
        + " "
        + classloader.get().hashCode()
        + "> "
        + className
        + "#"
        + methodSignature;
  }

  public boolean isBytecodeUpdated() {
    return stateCount.get() == lastUpdateStateCount.get();
  }

  public void markBytecodeUpdated() {
    lastUpdateStateCount.set(stateCount.get());
  }

  public void markBytecodeNotUpdated() {
    stateCount.incrementAndGet();
  }

  public void enableTracing(boolean allowTracing) {
    if (allowTracing) {
      if (tracingState.compareAndSet(TracingState.UNSET, TracingState.TRACING_ENABLED)) {
        markBytecodeNotUpdated();
      }
    } else {
      // unset -> disabled transitions do not require bytecode changes
      tracingState.compareAndSet(TracingState.UNSET, TracingState.TRACING_DISABLED);
      if (tracingState.compareAndSet(TracingState.TRACING_ENABLED, TracingState.TRACING_DISABLED)) {
        markBytecodeNotUpdated();
      }
    }
    if (!isBytecodeUpdated()) {
      log.debug("{} request set trace state to {}", this, tracingState.get());
      expand();
      graphMutator.updateBytecode(this);
    }
  }

  public boolean isTracingEnabled() {
    return tracingState.get() == TracingState.TRACING_ENABLED;
  }

  public boolean isExpanded() {
    return isExpanded.get();
  }

  public void expand() {
    if (isExpanded.compareAndSet(false, true)) {
      markBytecodeNotUpdated();
    }
    if (!isBytecodeUpdated()) {
      log.debug("{} request set expand state to {}", this, isExpanded.get());
      graphMutator.updateBytecode(this);
    }
  }

  public void addEdges(AutotraceNode... edgesToAdd) {
    for (AutotraceNode edgeToAdd : edgesToAdd) {
      if (edges.add(edgeToAdd)) {
        log.debug("{} : added edge: {}", this, edgesToAdd);
      }
    }
  }

  public Set<AutotraceNode> getEdges() {
    return edges;
  }

  public void addSuperNode(AutotraceNode superNode) {
    this.superNode.compareAndSet(null, superNode);
  }

  public AutotraceNode getSuperNode() {
    return superNode.get();
  }

  public void addImplementations(AutotraceNode... implementations) {
    for (AutotraceNode impl : implementations) {
      this.implNodes.add(impl);
    }
  }

  public List<AutotraceNode> getImplementations() {
    return Arrays.asList(implNodes.toArray(new AutotraceNode[0]));
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
