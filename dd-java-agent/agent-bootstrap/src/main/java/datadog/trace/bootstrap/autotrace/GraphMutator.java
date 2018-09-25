package datadog.trace.bootstrap.autotrace;

import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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

  /**
   * Block until this mutator has processed all events
   */
  public void blockProcess() {
    // default, non-async has no events backlogged
  }

  static class Async extends GraphMutator {
    private final Queue<Runnable> retransformQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger retransformCount = new AtomicInteger(0);
    private final Thread retransformThread = new Thread() {
      @Override
      public void run() {
        log.debug("Starting retransform thread: " + this);
        while (true) {
          for (Runnable task = retransformQueue.poll(); task != null; task = retransformQueue.poll()) {
            try {
              task.run();
            } catch (Exception e) {
              log.debug("Exception running retransform task" , e);
            }
          }
          try {
            Thread.sleep(1);
          } catch (InterruptedException e) {
          }
          retransformCount.incrementAndGet();
        }
      }
    };

    public Async(AutotraceGraph graph, Instrumentation instrumentation) {
      super(graph, instrumentation);
      retransformThread.setName("dd-retransform-thread");
      retransformThread.setDaemon(true);
      retransformThread.start();
    }

    @Override
    public void expand(final AutotraceNode node, final Runnable afterExpansionComplete) {
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
        retransformQueue.add(new Runnable() {
          @Override
          public void run() {
            retransformClasses(nodeClass);
            afterExpansionComplete.run();
            for (AutotraceNode implNode : node.getImplementations()) {
              implNode.expand();
            }
            final AutotraceNode superNode = node.getSuperNode();
            if (superNode != null) {
              superNode.expand();
            }
          }
        });
      } catch (Exception e) {
        log.debug("Failed to retransform bytecode for node " + node, e);
      }
    }

    @Override
    void updateTracingInstrumentation(final AutotraceNode node, final boolean doTrace, final Runnable afterTraceSet) {
      retransformQueue.add(new Runnable() {
        @Override
        public void run() {
          try {
            retransformClasses(node.getClassLoader().loadClass(node.getClassName()));
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
      });
    }

    @Override
    public void blockProcess() {
      try {
        while (retransformQueue.size() > 0) {
          Thread.sleep(1);
        }
        int currentIteration = retransformCount.get();
        while (retransformCount.get() == currentIteration) {
          Thread.sleep(1);
        }
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    }

    protected void retransformClasses(Class<?>... classes) {
      try {
        instrumentation.retransformClasses(classes);
      } catch (UnmodifiableClassException e) {
        log.debug("Failed to retransform classes", e);
      }
    }
  }
}
