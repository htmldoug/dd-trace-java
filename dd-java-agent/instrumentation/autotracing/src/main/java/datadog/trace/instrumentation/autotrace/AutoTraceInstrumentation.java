package datadog.trace.instrumentation.autotrace;

import static io.opentracing.log.Fields.ERROR_OBJECT;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.DDTransformers;
import datadog.trace.agent.tooling.ExceptionHandlers;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.Utils;
import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.bootstrap.autotrace.AutotraceGraph;
import datadog.trace.bootstrap.autotrace.AutotraceNode;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;
import java.security.ProtectionDomain;
import java.util.Collections;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;

@Slf4j
@AutoService(Instrumenter.class)
public final class AutoTraceInstrumentation extends Instrumenter.Default {

  public AutoTraceInstrumentation() {
    super("autotrace");
  }

  // TODO:
  @Override
  public AgentBuilder instrument(final AgentBuilder parentAgentBuilder) {
    // TODO: ugly
    // FIXME: strong classloader ref
    final ThreadLocal<ClassLoader> loaderUnderTransform = new ThreadLocal<>();
    final ThreadLocal<TypeDescription> typeUnderTransform = new ThreadLocal<>();

    return parentAgentBuilder
        .type(
            new AgentBuilder.RawMatcher() {
              @Override
              public boolean matches(
                  TypeDescription typeDescription,
                  ClassLoader classLoader,
                  JavaModule module,
                  Class<?> classBeingRedefined,
                  ProtectionDomain protectionDomain) {
                loaderUnderTransform.set(null);
                typeUnderTransform.set(null);
                if (AutotraceGraph.get().isDiscovered(classLoader, typeDescription.getName())) {
                  loaderUnderTransform.set(classLoader);
                  typeUnderTransform.set(typeDescription);
                  return true;
                } else {
                  return false;
                }
              }
            })
        .transform(DDTransformers.defaultTransformers())
        .transform(
            new AgentBuilder.Transformer.ForAdvice()
                .include(Utils.getAgentClassLoader())
                .withExceptionHandler(ExceptionHandlers.defaultExceptionHandler())
                .advice(
                    new ElementMatcher<MethodDescription>() {
                      @Override
                      public boolean matches(MethodDescription target) {
                        final String signature = target.getName() + target.getDescriptor();
                        final AutotraceNode node =
                            AutotraceGraph.get()
                                .getNode(
                                    loaderUnderTransform.get(),
                                    typeUnderTransform.get().getName(),
                                    signature,
                                    false);
                        boolean match =
                            !target.isConstructor() && node != null && node.isTracingEnabled();
                        if (match) {
                          log.debug("Applying autotrace tracing advice to: {}", target);
                        }
                        return match;
                      }
                    },
                    AutoTraceAdvice.class.getName()))
        .transform(
            new AgentBuilder.Transformer() {
              @Override
              public DynamicType.Builder<?> transform(
                  DynamicType.Builder<?> builder,
                  TypeDescription typeDescription,
                  ClassLoader classLoader,
                  JavaModule module) {
                // Hook up last to avoid discovering advice bytecode.
                // TODO: How to handle other instrumentation's bytecode?
                return builder.visit(
                    new MethodExpander(
                        classLoader,
                        typeDescription.getName(),
                        AutotraceGraph.get().getNodes(classLoader, typeDescription.getName())));
              }
            })
        .asDecorator();
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return new ElementMatcher<TypeDescription>() {
      @Override
      public boolean matches(TypeDescription target) {
        // FIXME
        return false;
      }
    };
  }

  @Override
  public Map<ElementMatcher, String> transformers() {
    // FIXME
    return Collections.EMPTY_MAP;
  }

  public static class AutoTraceAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Scope startSpan(
        @Advice.This final Object thiz,
        @Advice.Origin("#t") final String typeName,
        @Advice.Origin("#m") final String methodName,
        @Advice.Origin("#m#d") final String nodeSig) {
      final Span activeSpan = GlobalTracer.get().activeSpan();
      if (activeSpan != null) {
        final String autoTraceOpName =
            typeName.replaceAll("^.*\\.([^\\.]+)", "$1").replace('$', '_')
                + "."
                + methodName.replace('$', '_');
        if (((MutableSpan) activeSpan).getOperationName().equals(autoTraceOpName)) {
          // don't auto-trace recursive calls
          return null;
        }
        final Scope scope =
            GlobalTracer.get()
                // TODO: $ -> _ ??
                .buildSpan(autoTraceOpName)
                .withTag(Tags.COMPONENT.getKey(), "autotrace")
                .withTag("span.origin.type", thiz.getClass().getName())
                // TODO: something more human-readable than a descriptor
                .withTag("span.origin.method", nodeSig)
                .startActive(true);
        return scope;
      }
      // TODO: improve no-trace path
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpanAndExpand(
        @Advice.This final Object thiz,
        @Advice.Origin("#t") final String typeName,
        @Advice.Origin("#m#d") final String nodeSig,
        @Advice.Enter final Scope scope,
        @Advice.Thrown final Throwable throwable) {
      if (scope != null) {
        final AutotraceGraph graph = AutotraceGraph.get();
        if (throwable != null) {
          Tags.ERROR.set(scope.span(), true);
          scope.span().log(Collections.singletonMap(ERROR_OBJECT, throwable));
        }
        scope.close();
        final long spanDurationNano = ((MutableSpan) scope.span()).getDurationNano();
        if (spanDurationNano >= graph.getTraceMethodThresholdNanos()) {
          // expand nodes which exceed the threshold
          // TODO: retransform to remove unneeded expansion calls after first pass
          {
            final AutotraceNode node =
                graph.getNode(thiz.getClass().getClassLoader(), typeName, nodeSig, false);
            if (node != null) {
              node.expand();
              for (AutotraceNode edge : node.getEdges()) {
                edge.enableTracing(true);
              }
            }
          }
        } else if (spanDurationNano < graph.getDisableTraceThresholdNanos()) {
          final AutotraceNode node =
              graph.getNode(thiz.getClass().getClassLoader(), typeName, nodeSig, false);
          node.enableTracing(false);
        }
      }
    }
  }
}
