package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceCardinality;
import net.ximatai.muyun.spring.ability.reference.ReferencePlan;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PageReferenceProjectionReaderTest {
    @AfterEach void reset() { PlatformAbilityRuntime.resetReferenceTargetResolver(); }

    @Test
    void batchesTwoHopProjectionAndWritesNullForEmptySourceReference() {
        ReferenceTarget order = ReferenceTarget.of("sales.order", "purchase_main");
        ReferenceTarget supplier = ReferenceTarget.of("supply", "supplier");
        ReferenceTarget organization = ReferenceTarget.of("iam", "organization");
        @SuppressWarnings("unchecked") ReferenceAbility<?> supplierAbility = mock(ReferenceAbility.class);
        @SuppressWarnings("unchecked") ReferenceAbility<?> organizationAbility = mock(ReferenceAbility.class);
        when(supplierAbility.projections(any(), any())).thenReturn(Map.of(
                "supplier-a", Map.of("organizationId", "organization-a"),
                "supplier-b", Map.of("organizationId", "organization-b")));
        when(organizationAbility.projections(any(), any())).thenReturn(Map.of(
                "organization-a", Map.of("title", "华东机构")));
        PlatformAbilityRuntime.configureReferenceTargetResolver(new net.ximatai.muyun.spring.ability.reference.ReferenceTargetResolver() {
            @Override public Optional<ReferenceAbility<?>> resolve(ReferenceTarget target) {
                return supplier.equals(target) ? Optional.of(supplierAbility)
                        : organization.equals(target) ? Optional.of(organizationAbility) : Optional.empty();
            }
            @Override public Optional<ReferencePlan> referencePlan(ReferenceTarget target, String field) {
                if (order.equals(target) && "supplierId".equals(field))
                    return Optional.of(ReferencePlan.of(field, supplier, ReferenceCardinality.ONE));
                if (supplier.equals(target) && "organizationId".equals(field))
                    return Optional.of(ReferencePlan.of(field, organization, ReferenceCardinality.ONE));
                return Optional.empty();
            }
        });
        List<Map<String, Object>> records = List.of(
                new LinkedHashMap<>(Map.of("supplierId", "supplier-a")),
                new LinkedHashMap<>(Map.of("supplierId", "supplier-b")), new LinkedHashMap<>());

        PageReferenceProjectionReader.populate(order, records,
                List.of("supplierId.organizationId.title"), item -> item,
                (item, outputs) -> item.putAll(outputs));

        assertThat(records).extracting(item -> item.get("supplierId.organizationId.title"))
                .containsExactly("华东机构", null, null);
        verify(supplierAbility, times(1)).projections(any(), any());
        verify(organizationAbility, times(1)).projections(any(), any());
    }
}
