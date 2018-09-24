package datadog.trace.bootstrap.autotrace;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import lombok.extern.slf4j.Slf4j;

/** Means of updating the state of an autotrace graph. */
@Slf4j
abstract class GraphMutator {
  protected final Instrumentation instrumentation;
  protected final AutotraceGraph graph;

  public GraphMutator(AutotraceGraph graph, Instrumentation instrumentation) {
    this.instrumentation = instrumentation;
    this.graph = graph;
  }

  /**
   * Analyze a node and find all sub/super nodes.
   *
   * <p>This request is not guaranteed to actually be processed. Implementation may chose to drop
   * request for performance reasons.
   */
  abstract void expand(AutotraceNode node, Runnable afterExpansionComplete);

  /**
   * Update bytecode instrumentation of a node to reflect the node's tracing state.
   *
   * <p>This request is not guaranteed to actually be processed. Implementation may chose to drop
   * request for performance reasons.
   */
  abstract void updateTracingInstrumentation(
      AutotraceNode node, boolean doTrace, Runnable afterTracingSet);

  static class Blocking extends GraphMutator {
    public Blocking(AutotraceGraph graph, Instrumentation instrumentation) {
      super(graph, instrumentation);
    }

    @Override
    public void expand(AutotraceNode node, Runnable afterExpansionComplete) {
      try {
        // - get all loaded classes
        //   - find subtypes
        //   - find supertypes

        final Class<?> nodeClass = node.getClassLoader().loadClass(node.getClassName());

        if (!(node.isPrivate() || node.isStatic())) {
          // could use a for loop but I think that's harder to read
          Class<?> superClass = nodeClass.getSuperclass();
          boolean foundSuperMethod = false;
          while (superClass != null && !foundSuperMethod) {
            // TODO: duplicate code
            for (Method method : superClass.getDeclaredMethods()) {
              final String typeSignature =
                  method.getName() + AutotraceGraph.getMethodTypeDescriptor(method);
              if (node.getMethodTypeSignature().equals(typeSignature)) {
                AutotraceNode superNode =
                    graph.getNode(
                        superClass.getClassLoader(), superClass.getName(), typeSignature, true);
                node.addSuperNode(superNode);
                foundSuperMethod = true;
                break;
              }
            }
            superClass = superClass.getSuperclass();
          }
          final Class[] loadedClasses = instrumentation.getAllLoadedClasses();
          for (Class<?> clazz : loadedClasses) {
            if (nodeClass != clazz && nodeClass.isAssignableFrom(clazz)) {
              for (Method method : clazz.getDeclaredMethods()) {
                final String typeSignature =
                    method.getName() + AutotraceGraph.getMethodTypeDescriptor(method);
                if (node.getMethodTypeSignature().equals(typeSignature)) {
                  AutotraceNode implNode =
                      graph.getNode(clazz.getClassLoader(), clazz.getName(), typeSignature, true);
                  node.addImplementations(implNode);
                  break;
                }
              }
            }
          }
        }

        // run bytecode analysis and find all callers
        instrumentation.retransformClasses(nodeClass);
        afterExpansionComplete.run();
        for (AutotraceNode implNode : node.getImplementations()) {
          implNode.expand();
        }
        final AutotraceNode superNode = node.getSuperNode();
        if (superNode != null) {
          superNode.expand();
        }
      } catch (Exception e) {
        log.debug("Failed to retransform bytecode for node " + node, e);
      }
    }

    @Override
    void updateTracingInstrumentation(AutotraceNode node, boolean doTrace, Runnable afterTraceSet) {
      try {
        instrumentation.retransformClasses(node.getClassLoader().loadClass(node.getClassName()));
        afterTraceSet.run();
        if (doTrace) {
          node.expand();
          for (AutotraceNode implNode : node.getImplementations()) {
            implNode.enableTracing(doTrace);
          }
          final AutotraceNode superNode = node.getSuperNode();
          if (superNode != null) {
            superNode.enableTracing(doTrace);
          }
        }
      } catch (Exception e) {
        log.debug("Failed to retransform bytecode for node " + node, e);
        if (doTrace) {
          // stop trying to trace the node when unexpected exceptions occur
          node.enableTracing(false);
        }
      }
    }
  }

  // TODO
  static class Async extends GraphMutator {
    public Async(AutotraceGraph graph, Instrumentation instrumentation) {
      super(graph, instrumentation);
    }

    @Override
    public void expand(AutotraceNode node, Runnable afterExpansionComplete) {
      // async
      // 1) collect a list of all nodes to expand
      // 2) collect a list of all nodes to find sub and super hierarchies
      // 3) update rule list
      // 4) get all loaded classes, collect classes to retransform and check hierarchies
    }

    @Override
    void updateTracingInstrumentation(AutotraceNode node, boolean doTrace, Runnable afterTraceSet) {
      //
    }

    /**
     * A request to change the state of an {@link AutotraceNode}
     *
     * <p>A request may: - disable or enable tracing - expand the node
     */
    private abstract static class NodeChangeRequest {
      public NodeChangeRequest(
          AutotraceNode node, boolean doRetransform, boolean getSubtypes, boolean getSupertypes) {
        // todo: hook up to methods
      }

      public AutotraceNode getNode() {
        // TODO
        return null;
      }

      public boolean requestRetransform() {
        return false;
      }

      public boolean requestGetSubtypes() {
        return false;
      }

      public boolean requestGetSupertypes() {
        return false;
      }

      public static class Retransform extends NodeChangeRequest {
        public Retransform(AutotraceNode node) {
          super(node, true, false, false);
        }
      }
    }
  }
}
