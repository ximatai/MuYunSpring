package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.formula.FormulaProgram;

/** Server-resolved literal or PAGE_TEXT program for one non-interactive page copy slot. */
public record ResolvedPageTextDescriptor(String text, FormulaProgram program) {
    public ResolvedPageTextDescriptor {
        if ((text == null) == (program == null)) {
            throw new IllegalArgumentException("resolved page text requires exactly one of text or program");
        }
    }
}
