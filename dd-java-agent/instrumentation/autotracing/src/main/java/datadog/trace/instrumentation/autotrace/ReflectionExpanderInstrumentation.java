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
import org.slf4j.Logger;
import sun.reflect.Reflection;

@AutoService(Instrumenter.class)
public class ReflectionExpanderInstrumentation extends Instrumenter.Default {
  public ReflectionExpanderInstrumentation() {
    // TODO: Use same name/config as autotrace. Doesn't make sense to run one without the other.
    super("reflection-method");
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
      if (GlobalTracer.get().activeSpan() != null) {
        final Logger log = org.slf4j.LoggerFactory.getLogger("autodiscovery-reflection-expander");
        final Class<?> callerClass = Reflection.getCallerClass();
        if (AutotraceGraph.get().isDiscovered(callerClass.getClassLoader(), callerClass.getName())) {
          // TODO: It would be better to only discover nodes if the caller method is discovered,
          // but I don't know how to find the caller method without getting a stack trace (slow).

          final AutotraceGraph graph = AutotraceGraph.get();
          if (method.getDeclaringClass().getName().contains("spock")) {
            // skip spock unit-test calls
            return;
          }
          AutotraceNode calleeNode;
          if (Modifier.isStatic(method.getModifiers())) {
            calleeNode =
              graph.getNode(
                method.getDeclaringClass().getClassLoader(),
                method.getDeclaringClass().getName(),
                method.getName() + AutotraceGraph.getMethodTypeDescriptor(method),
                false);
          } else {
            calleeNode =
              graph.getNode(
                callee.getClass().getClassLoader(),
                callee.getClass().getName(),
                method.getName() + AutotraceGraph.getMethodTypeDescriptor(method),
                false);
          }
          if (calleeNode == null) {
            calleeNode =
              graph.getNode(
                method.getDeclaringClass().getClassLoader(),
                method.getDeclaringClass().getName(),
                method.getName() + AutotraceGraph.getMethodTypeDescriptor(method),
                true);
            log.debug("Discovered via reflection method invoke: {}", calleeNode);
            calleeNode.enableTracing(true);
          }
        }
      }
    }
  }
}
