package datadog.trace.bootstrap.autotrace;

import datadog.trace.bootstrap.WeakMap;
import datadog.trace.bootstrap.WeakMap.Provider;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * A graph of autotrace nodes and tracing rules.
 *
 * <p>TODO: doc class and methods
 */
@Slf4j
public class AutotraceGraph {
  private static final AtomicReference<AutotraceGraph> globalGraph = new AtomicReference<>(null);

  private final ClassLoader bootstrapProxy;
  private final GraphMutator mutator;
  private final long traceMethodThresholdNanos;
  private final long disableTraceThresholdNanos;
  private final WeakMap<ClassLoader, Map<String, List<AutotraceNode>>> nodeMap =
      Provider.<ClassLoader, Map<String, List<AutotraceNode>>>newWeakMap();

  public AutotraceGraph(
      ClassLoader bootstrapProxy,
      GraphMutator mutator,
      long traceMethodThresholdNanos,
      long disableTraceThresholdNanos) {
    this.bootstrapProxy = bootstrapProxy;
    this.mutator = mutator;
    this.traceMethodThresholdNanos = traceMethodThresholdNanos;
    this.disableTraceThresholdNanos = disableTraceThresholdNanos;
  }

  // TODO: rename -> awaitUpdates
  public void blockProcess() {
    mutator.awaitUpdates();
  }

  public long getTraceMethodThresholdNanos() {
    return traceMethodThresholdNanos;
  }

  public long getDisableTraceThresholdNanos() {
    return disableTraceThresholdNanos;
  }

  public static AutotraceGraph get() {
    return globalGraph.get();
  }

  public static AutotraceGraph set(AutotraceGraph newGraph) {
    globalGraph.set(newGraph);
    return get();
  }

  /** Get a node from the graph. */
  public AutotraceNode getNode(
      ClassLoader classloader,
      String className,
      String methodTypeSignature,
      boolean createIfNotExist) {
    if (null == classloader) classloader = bootstrapProxy;
    Map<String, List<AutotraceNode>> classMap = nodeMap.get(classloader);
    if (classMap == null) {
      if (createIfNotExist) {
        synchronized (nodeMap) {
          if (nodeMap.get(classloader) == null) {
            nodeMap.put(classloader, new ConcurrentHashMap<String, List<AutotraceNode>>());
          }
        }
        classMap = nodeMap.get(classloader);
      } else {
        return null;
      }
    }

    List<AutotraceNode> nodes = classMap.get(className);
    if (nodes == null) {
      if (createIfNotExist) {
        synchronized (classMap) {
          if (!classMap.containsKey(className)) {
            classMap.put(className, new CopyOnWriteArrayList<AutotraceNode>());
          }
        }
        nodes = classMap.get(className);
      } else {
        return null;
      }
    }

    AutotraceNode discovered = null;
    for (final AutotraceNode node : nodes) {
      if (node.getMethodTypeSignature().equals(methodTypeSignature)) {
        discovered = node;
        break;
      }
    }
    if (discovered == null && createIfNotExist) {
      discovered = new AutotraceNode(mutator, classloader, className, methodTypeSignature);
      nodes.add(discovered);
    }
    return discovered;
  }

  public boolean isDiscovered(ClassLoader classloader, String className, String typeSignature) {
    return getNode(classloader, className, typeSignature, false) != null;
  }

  public List<AutotraceNode> getNodes(ClassLoader classloader, String className) {
    if (null == classloader) classloader = bootstrapProxy;
    if (nodeMap.containsKey(classloader)) {
      // TODO callers can mutate
      return nodeMap.get(classloader).get(className);
    }
    return null;
  }

  public boolean isDiscovered(ClassLoader classloader, String className) {
    final List<AutotraceNode> nodes = getNodes(classloader, className);
    return nodes != null && nodes.size() > 0;
  }

  private static String getDescriptorForClass(final Class clazz) {
    if (clazz.isPrimitive()) {
      if (clazz == boolean.class) return "Z";
      if (clazz == byte.class) return "B";
      if (clazz == char.class) return "C";
      if (clazz == double.class) return "D";
      if (clazz == float.class) return "F";
      if (clazz == int.class) return "I";
      if (clazz == long.class) return "J";
      if (clazz == short.class) return "S";
      if (clazz == void.class) return "V";
      throw new RuntimeException("Unknown class: " + clazz);
    } else if (clazz.isArray()) {
      return clazz.getName().replace('.', '/');
    }
    return 'L' + clazz.getName().replace('.', '/') + ';';
  }

  /**
   * methodName+bytecodeMethodDescriptor
   *
   * <p>example: String foo(boolean b) {} --> foo(Z)Ljava/lang/String;
   */
  public static String getMethodTypeDescriptor(Method method) {
    final StringBuilder sb = new StringBuilder("(");
    for (final Class paramClass : method.getParameterTypes()) {
      sb.append(getDescriptorForClass(paramClass));
    }
    sb.append(")");
    sb.append(getDescriptorForClass(method.getReturnType()));
    return sb.toString();
  }
}
