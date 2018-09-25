package datadog.trace.instrumentation.autotrace;

import datadog.trace.agent.tooling.Utils;
import datadog.trace.bootstrap.autotrace.AutotraceGraph;
import datadog.trace.bootstrap.autotrace.AutotraceNode;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.pool.TypePool;

/** TODO: Doc */
@Slf4j
public class MethodExpander implements AsmVisitorWrapper {
  private final List<AutotraceNode> nodesToExpand;
  private final ClassLoader classLoader;
  private final String className;

  public MethodExpander(
      ClassLoader classLoader, String className, List<AutotraceNode> nodesToExpand) {
    log.debug("autotrace expansion for {}", className);
    for (final AutotraceNode node : nodesToExpand) {
      if (!node.getClassName().equals(className)) {
        throw new IllegalStateException(
            "Node <" + node + " does not match visited type: " + className);
      }
      log.debug(" -- search for {}", node.getMethodTypeSignature());
    }
    this.nodesToExpand = nodesToExpand;
    this.className = className;
    this.classLoader = classLoader;
  }

  @Override
  public int mergeWriter(int flags) {
    return flags;
  }

  @Override
  public int mergeReader(int flags) {
    return flags;
  }

  @Override
  public ClassVisitor wrap(
      TypeDescription instrumentedType,
      ClassVisitor classVisitor,
      Implementation.Context implementationContext,
      TypePool typePool,
      FieldList<FieldDescription.InDefinedShape> fields,
      MethodList<?> methods,
      int writerFlags,
      int readerFlags) {
    if (className.equals(instrumentedType.getName())) {
      classVisitor = new ExpansionVisitor(classVisitor);
    } else {
      log.debug(
          "Skipping expansion for {}. Class name does not match expected name: {}",
          instrumentedType.getName(),
          className);
    }
    return classVisitor;
  }

  private class ExpansionVisitor extends ClassVisitor {

    public ExpansionVisitor(ClassVisitor classVisitor) {
      super(Opcodes.ASM6, classVisitor);
    }

    @Override
    public MethodVisitor visitMethod(
        int access, String name, String descriptor, String signature, String[] exceptions) {
      MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
      final String nodeSignature = name + descriptor;
      for (final AutotraceNode node : nodesToExpand) {
        if (node.getMethodTypeSignature().equals(nodeSignature)) {
          log.debug("Applying autotrace expansion to {}.{}{}", className, name, descriptor);
          mv = new ExpansionMethodVisitor(node, mv);
        }
      }
      return mv;
    }

    private class ExpansionMethodVisitor extends MethodVisitor {
      private final List<AutotraceNode> edges = new ArrayList<>();
      private final AutotraceNode node;

      public ExpansionMethodVisitor(AutotraceNode node, MethodVisitor methodVisitor) {
        super(Opcodes.ASM6, methodVisitor);
        this.node = node;
      }

      private void addEdge(String className, String methodName, String methodDesc) {
        try {
          final AutotraceNode edge =
            AutotraceGraph.get()
              .getNode(
                // TODO: don't load classes here (under a transformer)
                classLoader.loadClass(className).getClassLoader(),
                className,
                methodName + methodDesc,
                true);
          if (!edges.contains(edge)) {
            // TODO: rm this debug or clean up
            log.debug(" -- found edge: {}#{}{}", className, methodName, methodDesc);
            edges.add(edge);
          }
        } catch (ClassNotFoundException e) {
          log.debug("exception discovering edge: " + className + "#" + methodName + methodDesc, e);
        }
      }

      @Override
      public void visitMethodInsn(int opcode, String owner, String name, String descriptor) {
        addEdge(className, name, descriptor);
        super.visitMethodInsn(opcode, owner, name, descriptor);
      }

      @Override
      public void visitMethodInsn(
          int opcode, String owner, String name, String descriptor, boolean isInterface) {
        final String className = Utils.getClassName(owner);
        addEdge(className, name, descriptor);
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
      }

      @Override
      public void visitEnd() {
        node.addEdges(edges);
        super.visitEnd();
      }
    }
  }
}
