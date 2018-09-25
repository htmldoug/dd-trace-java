package datadog.trace.bootstrap.autotrace;

import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import lombok.extern.slf4j.Slf4j;

@Slf4j
abstract class AbstractGraphMutator implements GraphMutator{
  protected final Instrumentation instrumentation;
  protected final AutotraceGraph graph;

  public AbstractGraphMutator(AutotraceGraph graph, Instrumentation instrumentation) {
    this.instrumentation = instrumentation;
    this.graph = graph;
  }

  abstract void expand(AutotraceNode node);

  abstract void updateTracingInstrumentation(AutotraceNode node);

  /**
   * Block until this mutator has processed all events
   */
  public void blockProcess() {
    // default, non-async has no events backlogged
  }

  /*
  static class Async extends AbstractGraphMutator {
    private final Queue<RetransformRequest> retransformQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger retransformCount = new AtomicInteger(0);
    private final Thread retransformThread = new Thread() {
      @Override
      public void run() {
        log.debug("Starting retransform thread: " + this);
        final Map<AutotraceNode, Class> toRetransform = new HashMap<>();
        final List<Runnable> callbacks = new ArrayList<>();
        int numRetransforms = 0;
        while (true) {
          for (RetransformRequest request = retransformQueue.poll(); request != null; request = retransformQueue.poll()) {
            if (!toRetransform.containsKey(request.node)) {
              // drop duplicate classes for retransformation
              try {
                Class<?> clazz = request.node.getClassLoader().loadClass(request.node.getClassName());
                if (!toRetransform.containsValue(clazz) && clazz.getName().startsWith("net.bytebuddy")) {
                  toRetransform.put(request.node, clazz);
                }
              } catch (Throwable t) {
                log.debug("Exception loading node class: " + request.node , t);
                // TODO: rm
                System.out.println("-- Exception during retransform load: " + t);
                t.printStackTrace();
              }
            }
            callbacks.add(request.successCallback);
          }

          try {
            if (toRetransform.size() > 0) {
              {
                final Class[] classes = toRetransform.values().toArray(new Class[0]);
                // TODO: rm
                System.out.println("-- retransforming " + classes.length + " classes");
                instrumentation.retransformClasses(classes);
                toRetransform.clear();
              }
              for (final Runnable callback : callbacks) {
                callback.run();
              }
              callbacks.clear();
            }
            Thread.sleep(1);
          } catch (Throwable t) {
            toRetransform.clear();
            callbacks.clear();
            log.debug("Exception running retransform task" , t);
            // TODO: rm
            System.out.println("-- Exception during retransform: " + t);
            t.printStackTrace();
          }
          // retransformCount.incrementAndGet();
          // TODO: rm
          if (retransformCount.incrementAndGet() % 1000 == 0) {
            System.out.println("-- Ran retransforms: " + numRetransforms);
            numRetransforms = 0;
          }
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
      retransformQueue.add(new RetransformRequest(node, new Runnable() {
        @Override
        public void run() {
          afterExpansionComplete.run();

          // search loaded classes for super/sub methods
          try {
            if (!(node.isPrivate() || node.isStatic())) {
              final Class<?> nodeClass = node.getClassLoader().loadClass(node.getClassName());
              Class<?> superClass = nodeClass.getSuperclass();
              boolean foundSuperMethod = false;
              while (superClass != null && (!superClass.getName().startsWith("java.")) && !foundSuperMethod) {
                instrumentation.retransformClasses(superClass);
                // TODO: duplicate code
                // TODO: speed up by checking for delcared method specific to node
                // for (Method method : superClass.getDeclaredMethods()) {
//                   final String typeSignature =
  //                   method.getName() + AutotraceGraph.getMethodTypeDescriptor(method);
    //               if (node.getMethodTypeSignature().equals(typeSignature)) {
      //               AutotraceNode superNode =
        //               graph.getNode(
          //               superClass.getClassLoader(), superClass.getName(), typeSignature, true);
            //         node.addSuperNode(superNode);
              //       foundSuperMethod = true;
                //     break;
//                   }
  //               }
                superClass = superClass.getSuperclass();
              }
              final Class[] loadedClasses = instrumentation.getAllLoadedClasses();
              for (Class<?> clazz : loadedClasses) {
                if (nodeClass != clazz && nodeClass.isAssignableFrom(clazz)) {
                  instrumentation.retransformClasses(clazz);
//                  for (Method method : clazz.getDeclaredMethods()) {
//                    final String typeSignature =
//                      method.getName() + AutotraceGraph.getMethodTypeDescriptor(method);
//                    if (node.getMethodTypeSignature().equals(typeSignature)) {
//                      AutotraceNode implNode =
//                        graph.getNode(clazz.getClassLoader(), clazz.getName(), typeSignature, true);
//                      node.addImplementations(implNode);
//                      break;
//                    }
//                  }
                }
              }
            }
          } catch (Exception e) {
            log.debug("Failed to retransform bytecode for node " + node, e);
          }

          // run bytecode analysis and find all callers
          for (AutotraceNode implNode : node.getImplementations()) {
            implNode.expand();
          }
          final AutotraceNode superNode = node.getSuperNode();
          if (superNode != null) {
            superNode.expand();
          }
        }
      }));
    }

    @Override
    void updateTracingInstrumentation(final AutotraceNode node, final boolean doTrace, final Runnable afterTraceSet) {
      retransformQueue.add(new RetransformRequest(node, new Runnable() {
        @Override
        public void run() {
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
        }
      }));
    }

    @Override
    public void blockProcess() {
      try {
        while (retransformQueue.size() > 0) {
          int awaitIteration = retransformCount.get() + 10;
          while (retransformCount.get() < awaitIteration) {
            Thread.sleep(1);
          }
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

    private static class RetransformRequest {
      public final AutotraceNode node;
      public final Runnable successCallback;

      public RetransformRequest(AutotraceNode node, Runnable successCallback) {
        this.node = node;
        this.successCallback = successCallback;
      }
    }
  }
  */
}
