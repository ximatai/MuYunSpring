package net.ximatai.muyun.spring.dynamic.web;

import net.ximatai.muyun.spring.web.TreeSortScopeRequest;
import net.ximatai.muyun.spring.web.TreeSortWebRequest;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicEntityOperations;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicEntityDescriptor;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DynamicCapabilityWebActionAdapterTest {
    @Test
    void shouldRejectScopeForNonTreeEntity() {
        TreeSortWebRequest request = new TreeSortWebRequest("previous", null, null,
                new TreeSortScopeRequest(Map.of("organizationId", "org-1"),
                        "iam.organization", "organization"));

        DynamicEntityOperations target = mock(DynamicEntityOperations.class);
        DynamicEntityDescriptor descriptor = mock(DynamicEntityDescriptor.class);
        when(target.describe()).thenReturn(descriptor);
        when(descriptor.capabilities()).thenReturn(java.util.Set.of("SORT"));
        assertThatThrownBy(() -> DynamicCapabilityWebActionAdapter.sort(
                null, target, "sales.contract", "contract", "record-1", request, Criteria.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("tree sort scope requires TREE capability");
    }
}
