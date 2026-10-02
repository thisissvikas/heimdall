package dev.heimdall.assertions;

import dev.cel.common.types.SimpleType;
import dev.cel.compiler.*;
import dev.cel.parser.CelStandardMacro;
import dev.cel.runtime.*;
import java.util.Map;

public final class Expressions {
  private static final CelCompiler COMPILER =
      CelCompilerFactory.standardCelCompilerBuilder()
          .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
          .addVar("response", SimpleType.DYN)
          .addVar("vars", SimpleType.DYN)
          .addVar("env", SimpleType.DYN)
          .addVar("run", SimpleType.DYN)
          .build();
  private static final CelRuntime RUNTIME = CelRuntimeFactory.plannerRuntimeBuilder().build();

  public static void validate(String expression) {
    compile(expression);
  }

  private static CelRuntime.Program compile(String expression) {
    if (expression == null || expression.length() > 8192)
      throw new IllegalArgumentException("Invalid expression size");
    try {
      return RUNTIME.createProgram(COMPILER.compile(expression).getAst());
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid CEL expression", e);
    }
  }

  public static boolean test(String expression, Map<String, Object> context) {
    try {
      Object value = compile(expression).eval(context);
      if (!(value instanceof Boolean b))
        throw new IllegalArgumentException("Expression must return boolean");
      return b;
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException("Expression evaluation failed", e);
    }
  }
}
