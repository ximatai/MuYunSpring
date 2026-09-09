package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageReferencePathCompilerTest {
    @Test
    void rejectsBlankAndOverDeepPathsBeforeResolvingAnyRuntimeReference() {
        ReferenceTarget root = ReferenceTarget.of("sales.order", "purchase_root");
        assertThatThrownBy(() -> PageReferencePathCompiler.compile(root, "supplierId.title."))
                .hasMessageContaining("blank segment");
        assertThatThrownBy(() -> PageReferencePathCompiler.compile(root,
                "a.b.c.d.e.f.g.title"))
                .hasMessageContaining("exceeds " + RelationProjectionPlanningOptions.DEFAULT_MAX_JOIN_DEPTH);
    }
}
