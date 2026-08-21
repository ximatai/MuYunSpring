package net.ximatai.muyun.spring.platform.ui;

import net.ximatai.muyun.spring.common.formula.FormulaProgram;

/** Source-neutral UI formula already compiled by the server for browser execution. */
public record ResolvedUiFormula(String expression, FormulaProgram program) {
}
