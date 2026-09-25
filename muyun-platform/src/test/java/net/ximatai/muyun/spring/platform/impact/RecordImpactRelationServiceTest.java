package net.ximatai.muyun.spring.platform.impact;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicMutationContext;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecordImpactRelationServiceTest {
    private final RecordImpactRelationService service = new RecordImpactRelationService(new TestMemoryDao<>());

    @Test
    void shouldNotRegisterWithoutOriginContext() {
        RecordImpactRelation relation = service.registerFromOriginContext(null, "target-1", "operator-1");

        assertThat(relation).isNull();
        assertThat(service.listByTarget("sales.contract", "target-1")).isEmpty();
    }

    @Test
    void shouldFailFastWhenTargetModuleAliasMismatch() {
        RecordOriginContext context = originContext("sales.contract");

        assertThatThrownBy(() -> service.registerFromOriginContext(context,
                        "sales.invoice",
                        "invoice-1",
                        "operator-1"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("targetModuleAlias mismatch");
    }

    @Test
    void shouldRegisterRelationFromOriginContextWithSavedTargetRecord() {
        RecordImpactRelation relation = service.registerFromOriginContext(
                originContext("sales.contract"), "contract-1", "operator-1");

        assertThat(relation.getId()).isNotBlank();
        assertThat(relation.getImpactType()).isEqualTo(RecordImpactType.GENERATE_PUSH);
        assertThat(relation.getSourceModuleAlias()).isEqualTo("sales.opportunity");
        assertThat(relation.getSourceRecordId()).isEqualTo("opp-1");
        assertThat(relation.getTargetModuleAlias()).isEqualTo("sales.contract");
        assertThat(relation.getTargetRecordId()).isEqualTo("contract-1");
        assertThat(relation.getGenerationRuleId()).isEqualTo("rule-1");
        assertThat(relation.getActionCode()).isEqualTo("generateContract");
        assertThat(relation.getBatchId()).isEqualTo("batch-1");
        assertThat(relation.getDraftKey()).isEqualTo("draft-1");
        assertThat(relation.getOperatorUserId()).isEqualTo("operator-1");
    }

    @Test
    void shouldRejectBlankSourceAndTargetRecordFields() {
        assertThatThrownBy(() -> service.registerFromOriginContext(
                        new RecordOriginContext(RecordImpactType.GENERATE_PUSH,
                                "sales.opportunity", " ", "sales.contract",
                                "rule-1", "generateContract", "batch-1", "draft-1"),
                        "contract-1",
                        "operator-1"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("sourceRecordId");

        assertThatThrownBy(() -> service.registerFromOriginContext(
                        originContext("sales.contract"),
                        " ",
                        "operator-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targetRecordId");
    }

    @Test
    void shouldAllowSameSourceAndRuleToImpactMultipleTargets() {
        service.registerFromOriginContext(originContext("sales.contract"), "contract-1", "operator-1");
        service.registerFromOriginContext(originContext("sales.contract"), "contract-2", "operator-1");

        assertThat(service.listBySource("sales.opportunity", "opp-1"))
                .extracting(RecordImpactRelation::getTargetRecordId)
                .containsExactlyInAnyOrder("contract-1", "contract-2");
    }

    @Test
    void shouldQueryBySourceAndTarget() {
        service.registerFromOriginContext(originContext("sales.contract"), "contract-1", "operator-1");
        service.registerFromOriginContext(
                new RecordOriginContext(RecordImpactType.GENERATE_PUSH,
                        "sales.opportunity", "opp-2", "sales.contract",
                        "rule-1", "generateContract", "batch-2", "draft-1"),
                "contract-2",
                "operator-2");

        assertThat(service.listBySource("sales.opportunity", "opp-1"))
                .singleElement()
                .extracting(RecordImpactRelation::getTargetRecordId)
                .isEqualTo("contract-1");
        assertThat(service.listByTarget("sales.contract", "contract-2"))
                .singleElement()
                .extracting(RecordImpactRelation::getSourceRecordId)
                .isEqualTo("opp-2");
    }

    @Test
    void shouldQueryGeneratedRelationsByBatchRuleAndSourceTarget() {
        service.registerFromOriginContext(originContext("sales.contract"), "contract-1", "operator-1");
        service.registerFromOriginContext(
                new RecordOriginContext(RecordImpactType.GENERATE_PUSH,
                        "sales.opportunity", "opp-1", "finance.invoice",
                        "rule-2", "generateInvoice", "batch-1", "invoice-1"),
                "invoice-1",
                "operator-1");
        service.registerFromOriginContext(
                new RecordOriginContext(RecordImpactType.GENERATE_PUSH,
                        "sales.opportunity", "opp-2", "sales.contract",
                        "rule-1", "generateContract", "batch-2", "draft-1"),
                "contract-2",
                "operator-2");

        assertThat(service.listByBatch("batch-1"))
                .extracting(RecordImpactRelation::getTargetRecordId)
                .containsExactlyInAnyOrder("contract-1", "invoice-1");
        assertThat(service.listByGenerationRule("rule-1"))
                .extracting(RecordImpactRelation::getTargetRecordId)
                .containsExactlyInAnyOrder("contract-1", "contract-2");
        assertThat(service.listGeneratedTargets("sales.opportunity", "opp-1",
                        "sales.contract", "rule-1", null))
                .singleElement()
                .extracting(RecordImpactRelation::getTargetRecordId)
                .isEqualTo("contract-1");
        assertThat(service.hasGeneratedTarget("sales.opportunity", "opp-1",
                "sales.contract", "rule-1")).isTrue();
        assertThat(service.hasGeneratedTarget("sales.opportunity", "opp-1",
                "sales.contract", "rule-missing")).isFalse();
    }

    @Test
    void shouldRegisterRelationFromMutationOriginContextAfterTargetCreate() {
        RecordImpactOriginCoordinator coordinator = new RecordImpactOriginCoordinator(service);
        try (CurrentUserContext.Scope ignoredUser = CurrentUserContext.use(CurrentUser.tenantUser("user-1", "User", "tenant-1"));
             DynamicMutationContext ignored = DynamicMutationContext.open(Clock.systemUTC(), Map.of(
                     RecordImpactOriginCoordinator.ORIGIN_CONTEXT_KEY, originContext("sales.contract")))) {
            coordinator.afterCreate("sales.contract", "contract", null, "contract-1");
        }

        assertThat(service.listBySource("sales.opportunity", "opp-1"))
                .singleElement()
                .satisfies(relation -> {
                    assertThat(relation.getTargetRecordId()).isEqualTo("contract-1");
                    assertThat(relation.getOperatorUserId()).isEqualTo("user-1");
                });
    }

    @Test
    void shouldRegisterRelationFromMutationOriginContextMapAfterTargetUpdate() {
        RecordImpactOriginCoordinator coordinator = new RecordImpactOriginCoordinator(service);
        try (DynamicMutationContext ignored = DynamicMutationContext.open(Clock.systemUTC(), Map.of(
                RecordImpactOriginCoordinator.ORIGIN_CONTEXT_KEY,
                Map.of(
                        "impactType", "GENERATE_PUSH",
                        "sourceModuleAlias", "sales.opportunity",
                        "sourceRecordId", "opp-1",
                        "targetModuleAlias", "sales.contract",
                        "generationRuleId", "rule-1",
                        "actionCode", "generateContract",
                        "batchId", "batch-1",
                        "draftKey", "draft-1"
                )))) {
            net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord updated = new net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord(
                    new net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition("contract", "sales_contract", "Contract",
                            java.util.List.of(net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition.string("name", "Name"))));
            updated.setId("contract-1");
            coordinator.afterUpdate("sales.contract", "contract", null, updated);
        }

        assertThat(service.listByTarget("sales.contract", "contract-1"))
                .singleElement()
                .extracting(RecordImpactRelation::getSourceRecordId)
                .isEqualTo("opp-1");
    }

    @Test
    void shouldNotRegisterRelationWithoutMutationOriginContext() {
        RecordImpactOriginCoordinator coordinator = new RecordImpactOriginCoordinator(service);

        coordinator.afterCreate("sales.contract", "contract", null, "contract-1");

        assertThat(service.listByTarget("sales.contract", "contract-1")).isEmpty();
    }

    @Test
    void shouldRejectMutationOriginContextTargetMismatch() {
        RecordImpactOriginCoordinator coordinator = new RecordImpactOriginCoordinator(service);

        assertThatThrownBy(() -> {
            try (DynamicMutationContext ignored = DynamicMutationContext.open(Clock.systemUTC(), Map.of(
                    RecordImpactOriginCoordinator.ORIGIN_CONTEXT_KEY, originContext("sales.contract")))) {
                coordinator.afterCreate("sales.invoice", "invoice", null, "invoice-1");
            }
        }).isInstanceOf(PlatformException.class)
                .hasMessageContaining("targetModuleAlias mismatch");
        assertThat(service.listByTarget("sales.invoice", "invoice-1")).isEmpty();
    }

    private RecordOriginContext originContext(String targetModuleAlias) {
        return new RecordOriginContext(
                RecordImpactType.GENERATE_PUSH,
                "sales.opportunity",
                "opp-1",
                targetModuleAlias,
                "rule-1",
                "generateContract",
                "batch-1",
                "draft-1"
        );
    }
}
