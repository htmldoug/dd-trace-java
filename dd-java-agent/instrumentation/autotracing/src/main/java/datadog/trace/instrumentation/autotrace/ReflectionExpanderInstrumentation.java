package datadog.trace.instrumentation.autotrace;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.autotrace.AutotraceGraph;
import datadog.trace.bootstrap.autotrace.AutotraceNode;
import io.opentracing.util.GlobalTracer;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class ReflectionExpanderInstrumentation extends Instrumenter.Default {
  public ReflectionExpanderInstrumentation() {
    // TODO: Use same name/config as autotrace. Doesn't make sense to run one without the other.
    super("reflection-method");
  }

  @Override
  protected boolean defaultEnabled() {
    return false;
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("java.lang.reflect.Method");
  }

  @Override
  public Map<ElementMatcher, String> transformers() {
    return Collections.<ElementMatcher, String>singletonMap(
        named("invoke")
            .and(takesArgument(0, Object.class))
            .and(takesArgument(1, Object[].class))
            .and(takesArguments(2)),
        MethodInvokeAdvice.class.getName());
  }

  public static class MethodInvokeAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void expandAutotrace(
        @Advice.This final java.lang.reflect.Method method, @Advice.Argument(0) Object callee) {
      /*
      final Class<?> callerClass = Reflection.getCallerClass();
      if (AutotraceGraph.isDiscovered(callerClass.getClassLoader(), callerClass.getName())) {
        // final AutotraceNode callerNode = AutotraceGraph.getDiscoveredNode(callerClass.getClassLoader(), callerClass.getName(), ??);

      }
      */

      // TODO: link caller and callee nodes
      // FIXME: Only do this if caller is already autotraced!
      if (GlobalTracer.get().activeSpan() != null) {
        final AutotraceGraph graph = AutotraceGraph.get();

        if (method.getDeclaringClass().getName().contains("spock")) {
          // TODO: remove
          return;
        }
        // TODO: static vs non-static calls
        if (Modifier.isStatic(method.getModifiers())) {
          final AutotraceNode calleeNode =
              graph.getNode(
                  method.getDeclaringClass().getClassLoader(),
                  method.getDeclaringClass().getName(),
                  method.getName() + AutotraceGraph.getMethodTypeDescriptor(method),
                  false);
          if (!calleeNode.isTracingEnabled()) {
            System.out.println("-- FOUND STATIC NODE VIA REFLECTION: " + calleeNode);
          }
          calleeNode.enableTracing(true);
        } else {
          if (callee.getClass().getName().startsWith("java.lang.reflect")) {
            // TODO: remove
            return;
          }
          final AutotraceNode calleeNode =
              graph.getNode(
                  callee.getClass().getClassLoader(),
                  callee.getClass().getName(),
                  method.getName() + AutotraceGraph.getMethodTypeDescriptor(method),
                  false);
          if (!calleeNode.isTracingEnabled()) {
            System.out.println("-- FOUND NON-STATIC NODE VIA REFLECTION: " + calleeNode);
          }
          calleeNode.enableTracing(true);
        }
      }
    }
  }
}
