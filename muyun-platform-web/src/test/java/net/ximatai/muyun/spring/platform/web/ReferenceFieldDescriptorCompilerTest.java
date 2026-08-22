package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.ability.reference.ReferenceCardinality;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReferenceFieldDescriptorCompilerTest {
    @Test
    void shouldPublishTreeParentAsSelfReferenceForAnyDescriptorSource() {
        Map<String, ResolvedReferenceFieldDescriptor> resolved =
                ReferenceFieldDescriptorCompiler.withTreeParentReference("crm.category", true, Map.of(),
                        ignored -> ReferencePickerMode.TREE);

        assertThat(resolved).containsOnlyKeys("parentId");
        assertThat(resolved.get("parentId")).isEqualTo(new ResolvedReferenceFieldDescriptor("crm.category",
                ReferenceCardinality.ONE, null, ReferencePickerMode.TREE));
    }

    @Test
    void shouldRejectANonSelfTreeParentReference() {
        ResolvedReferenceFieldDescriptor explicit = new ResolvedReferenceFieldDescriptor("crm.parent_category",
                ReferenceCardinality.ONE, "title", ReferencePickerMode.LIST);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                ReferenceFieldDescriptorCompiler.withTreeParentReference("crm.category", true,
                        Map.of("parentId", explicit), ignored -> ReferencePickerMode.TREE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tree parent reference must be a single self reference");
    }
}
