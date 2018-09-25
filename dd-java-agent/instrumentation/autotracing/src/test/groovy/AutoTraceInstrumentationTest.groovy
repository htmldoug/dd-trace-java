import datadog.trace.bootstrap.autotrace.AutotraceGraph
import io.opentracing.tag.Tags
import some.org.Helper
import some.org.SomeInterface
import spock.lang.Ignore

import java.lang.reflect.Method

import static datadog.trace.agent.test.TestUtils.runUnderTrace
import static datadog.trace.agent.test.asserts.ListWriterAssert.assertTraces

import datadog.trace.agent.test.AgentTestRunner

class AutoTraceInstrumentationTest extends AgentTestRunner {

  def "trace after discovery"() {
    setup:
    // TODO: make shared once test runner is fixed
    AutotraceGraph graph = AutotraceGraph.get()

    when:
    runUnderTrace("someTrace") {
      new Helper().someMethod(11)
    }

    then:
    assertTraces(TEST_WRITER, 1) {
      trace(0, 1) {
        span(0) {
          serviceName "unnamed-java-app"
          operationName "someTrace"
          errored false
          tags {
            defaultTags()
          }
        }
      }
    }

    when:
    TEST_WRITER.clear()
    graph.getNode(Helper.getClassLoader(), Helper.getName(), "someMethod(J)V", true).enableTracing(true)
    graph.blockProcess()
    runUnderTrace("someTrace") {
      new Helper().someMethod(11)
    }

    then:
    graph.isDiscovered(null, Thread.getName(), "sleep(J)V")
    assertTraces(TEST_WRITER, 1) {
      trace(0, 2) {
        span(0) {
          serviceName "unnamed-java-app"
          operationName "someTrace"
          errored false
          tags {
            defaultTags()
          }
        }
        span(1) {
          serviceName "unnamed-java-app"
          operationName Helper.getSimpleName() + '.someMethod'
          errored false
          tags {
            "$Tags.COMPONENT.key" "autotrace"
            "span.origin.type" "some.org.Helper"
            "span.origin.method" "someMethod(J)V"
            defaultTags()
          }
        }
      }
    }

    when:
    TEST_WRITER.clear()
    // someMethod should not create a span outside of a trace
    new Helper().someMethod(11)
    runUnderTrace("someTrace") {}
    then:
    assertTraces(TEST_WRITER, 1) {
      trace(0, 1) {
        span(0) {
          serviceName "unnamed-java-app"
          operationName "someTrace"
          errored false
          tags {
            defaultTags()
          }
        }
      }
    }

    when:
    TEST_WRITER.clear()
    boolean slowTestRun = false
    long t1 = System.nanoTime()
    runUnderTrace("someTrace") {
      new Helper().someMethod(0)
    }
    long duration = System.nanoTime() - t1
    if (duration >= graph.getTraceMethodThresholdNanos()) {
      // Trace is abnormally slow. Skip assertion.
      slowTestRun = true
    }
    TEST_WRITER.waitForTraces(1)
    graph.blockProcess()

    then:
    TEST_WRITER.size() == 1
    TEST_WRITER[0].size() == slowTestRun ? 2 : 1

    // TODO: test tracing disable
  }

  def "discovery expansion propagates"() {
    setup:
    AutotraceGraph graph = AutotraceGraph.get()

    when:
    // first-pass: n1 is manually discovered
    graph.getNode(Helper.getClassLoader(), Helper.getName(), "n1(JJJJ)V", true).enableTracing(true)
    graph.blockProcess()
    runUnderTrace("someTrace") {
      new Helper().n1(0, 0, 11, 0)
    }
    TEST_WRITER.waitForTraces(1)
    graph.blockProcess()

    then:
    TEST_WRITER.size() == 1
    TEST_WRITER[0].size() == 2

    // second-pass: n2 is automatically discovered
    when:
    TEST_WRITER.clear()
    runUnderTrace("someTrace") {
      new Helper().n1(0, 0, 11, 0)
    }
    TEST_WRITER.waitForTraces(1)
    graph.blockProcess()

    then:
    TEST_WRITER.size() == 1
    TEST_WRITER[0].size() == 3

    // third-pass: n3 is automatically discovered
    when:
    TEST_WRITER.clear()
    runUnderTrace("someTrace") {
      new Helper().n1(0, 0, 11, 0)
    }

    then:
    assertTraces(TEST_WRITER, 1) {
      trace(0, 4) {
        span(0) {
          serviceName "unnamed-java-app"
          operationName "someTrace"
          errored false
          tags {
            defaultTags()
          }
        }
        span(1) {
          serviceName "unnamed-java-app"
          operationName Helper.getSimpleName() + '.n1'
          errored false
          tags {
            "$Tags.COMPONENT.key" "autotrace"
            "span.origin.type" "some.org.Helper"
            "span.origin.method" "n1(JJJJ)V"
            defaultTags()
          }
        }
        span(2) {
          serviceName "unnamed-java-app"
          operationName Helper.getSimpleName() + '.n2'
          errored false
          tags {
            "$Tags.COMPONENT.key" "autotrace"
            "span.origin.type" "some.org.Helper"
            "span.origin.method" "n2(JJJ)V"
            defaultTags()
          }
        }
        span(3) {
          serviceName "unnamed-java-app"
          operationName Helper.getSimpleName() + '.n3'
          errored false
          tags {
            "$Tags.COMPONENT.key" "autotrace"
            "span.origin.type" "some.org.Helper"
            "span.origin.method" "n3(JJ)V"
            defaultTags()
          }
        }
      }
    }
  }

  def "auto instrumentation captures exceptions"() {
    setup:
    AutotraceGraph graph = AutotraceGraph.get()

    // TODO
    when:
    graph.getNode(Helper.getClassLoader(), Helper.getName(), "exceptionEater()V", true).enableTracing(true)
    graph.blockProcess()
    // warm-up
    runUnderTrace("someTrace") {
      new Helper().exceptionEater()
    }
    TEST_WRITER.waitForTraces(1)
    graph.blockProcess()
    TEST_WRITER.clear()
    runUnderTrace("someTrace") {
      new Helper().exceptionEater()
    }

    then:
    assertTraces(TEST_WRITER, 1) {
      trace(0, 3) {
        span(0) {
          serviceName "unnamed-java-app"
          operationName "someTrace"
          errored false
          tags {
            defaultTags()
          }
        }
        span(1) {
          serviceName "unnamed-java-app"
          operationName Helper.getSimpleName() + '.exceptionEater'
          errored false
          tags {
            "$Tags.COMPONENT.key" "autotrace"
            "span.origin.type" "some.org.Helper"
            "span.origin.method" "exceptionEater()V"
            defaultTags()
          }
        }
        span(2) {
          serviceName "unnamed-java-app"
          operationName Helper.getSimpleName() + '.exceptionThrower'
          errored true
          tags {
            "$Tags.COMPONENT.key" "autotrace"
            "span.origin.type" "some.org.Helper"
            "span.origin.method" "exceptionThrower()V"
            "$Tags.ERROR.key" true
            "error.msg" String
            "error.type" RuntimeException.getName()
            "error.stack" String
            defaultTags()
          }
        }
      }
    }
  }

  def "trace interface implementations" () {
    setup:
    AutotraceGraph graph = AutotraceGraph.get()

    when:
    // preload impl1
    graph.getNode(Helper.getClassLoader(), Helper.getName(), "interfaceInvoker(Lsome/org/SomeInterface;)V", true).enableTracing(true)
    graph.blockProcess()
    // TODO: rm println
    println 'TEST: --- Helper trace and expansion complete ---'
    // warm-up
    runUnderTrace("someTrace") {
      new Helper().interfaceInvoker(new SomeInterface.Impl1())
    }
    TEST_WRITER.waitForTraces(1)
    graph.blockProcess()
    println 'TEST: --- trace enabled on Impl1 ---'
    TEST_WRITER.clear()
    runUnderTrace("someTrace") {
      // impl1 already loaded. Discovered by expansion
      new Helper().interfaceInvoker(new SomeInterface.Impl1())
    }

   then:
    assertTraces(TEST_WRITER, 1) {
      trace(0, 3) {
        span(0) {
          serviceName "unnamed-java-app"
          operationName "someTrace"
          errored false
          tags {
            defaultTags()
          }
        }
        span(1) {
          serviceName "unnamed-java-app"
          operationName Helper.getSimpleName() + '.interfaceInvoker'
          errored false
          tags {
            "$Tags.COMPONENT.key" "autotrace"
            "span.origin.type" "some.org.Helper"
            "span.origin.method" "interfaceInvoker(Lsome/org/SomeInterface;)V"
            defaultTags()
          }
        }
        span(2) {
          serviceName "unnamed-java-app"
          operationName 'SomeInterface_Impl1.someMethod'
          errored false
          tags {
            "$Tags.COMPONENT.key" "autotrace"
            "span.origin.type" 'some.org.SomeInterface$Impl1'
            "span.origin.method" "someMethod(J)V"
            defaultTags()
          }
        }
      }
    }

   when:
   TEST_WRITER.clear()
   runUnderTrace("someTrace") {
     new Helper().interfaceInvoker(new SomeInterface.Impl2())
   }
   then:
   assertTraces(TEST_WRITER, 1) {
     trace(0, 3) {
       span(0) {
         serviceName "unnamed-java-app"
         operationName "someTrace"
         errored false
         tags {
           defaultTags()
         }
       }
       span(1) {
         serviceName "unnamed-java-app"
         operationName Helper.getSimpleName() + '.interfaceInvoker'
         errored false
         tags {
           "$Tags.COMPONENT.key" "autotrace"
           "span.origin.type" "some.org.Helper"
           "span.origin.method" "interfaceInvoker(Lsome/org/SomeInterface;)V"
           defaultTags()
         }
       }
       span(2) {
         serviceName "unnamed-java-app"
         operationName 'SomeInterface_Impl2.someMethod'
         errored false
         tags {
           "$Tags.COMPONENT.key" "autotrace"
           "span.origin.type" 'some.org.SomeInterface$Impl2'
           "span.origin.method" "someMethod(J)V"
           defaultTags()
         }
       }
     }
   }
  }

  // TODO: Reflection linking
  @Ignore
  def "trace reflection"() {
    when:
    Method m = Helper.getMethod("callByReflection")
    runUnderTrace("someTrace") {
      m.invoke(new Helper())
    }

    then:
    assertTraces(TEST_WRITER, 1) {
      trace(0, 2) {
        span(0) {
          serviceName "unnamed-java-app"
          operationName "someTrace"
          errored false
          tags {
            defaultTags()
          }
        }
        span(1) {
          serviceName "unnamed-java-app"
          operationName Helper.getSimpleName() + '.callByReflection'
          errored false
          tags {
            "$Tags.COMPONENT.key" "autotrace"
            "span.origin.type" "some.org.Helper"
            "span.origin.method" "callByReflection()V"
            defaultTags()
          }
        }
      }
    }
  }

  @Ignore
  def "trace async" () {
    expect:
    1 == 1
  }

  @Ignore
  def "trace override method" () {
    expect:
    1 == 1
  }

  @Ignore
  def "trace super method" () {
    expect:
    1 == 1
  }
}
