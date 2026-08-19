package net.ximatai.muyun.spring.common.formula;

/** Execution policy for a server-issued {@link FormulaProgram}; not a separate formula language. */
public enum FormulaExecutionProfile {
    WEB_UI,
    /**
     * A deterministic, browser-local main-record calculation. The program itself only describes
     * a patch; trigger ordering, dirty-value ownership and persistence remain the caller's job.
     */
    FORM_COMPUTE
}
