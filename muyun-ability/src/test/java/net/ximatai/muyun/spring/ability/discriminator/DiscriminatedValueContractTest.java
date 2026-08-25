package net.ximatai.muyun.spring.ability.discriminator;

import net.ximatai.muyun.spring.ability.reference.ReferenceCandidateBinding;
import net.ximatai.muyun.spring.ability.reference.ReferenceCardinality;
import net.ximatai.muyun.spring.ability.reference.StaticReferenceResolver;
import net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiscriminatedValueContractTest {
    @Test
    void shouldCompileEveryEnumBranchIntoOneSourceIndependentPlan() {
        DiscriminatedValuePlan plan = StaticReferenceResolver.discriminatedValuePlans(ScopedRecord.class).getFirst();

        assertThat(plan.discriminatorValues()).containsExactlyInAnyOrder("system", "tenant", "organization");
        assertThat(plan.caseFor(Scope.ORGANIZATION).reference().target().qualifiedName()).isEqualTo("iam.organization");
        assertThat(plan.caseFor("organization").reference().candidateDependencies())
                .containsExactly(new net.ximatai.muyun.spring.ability.reference.ReferenceCandidateDependency("tenantId", "tenantId", true));
    }

    @Test
    void shouldRejectMissingEnumBranchAtCompilation() {
        assertThatThrownBy(() -> StaticReferenceResolver.discriminatedValuePlans(IncompleteScopedRecord.class))
                .hasMessageContaining("must declare every enum case");
    }

    @Test
    void shouldRejectMultiValueReferenceBranchAtCompilation() {
        assertThatThrownBy(() -> StaticReferenceResolver.discriminatedValuePlans(MultiValueScopedRecord.class))
                .hasMessageContaining("discriminator reference must be ONE");
    }

    @Test
    void shouldRejectUnknownReferenceDependencySourceFieldAtCompilation() {
        assertThatThrownBy(() -> StaticReferenceResolver.discriminatedValuePlans(UnknownDependencyScopedRecord.class))
                .hasMessageContaining("dependency source field does not exist: missingTenantId");
    }

    enum Scope implements CodeTitleEnum {
        SYSTEM("system"), TENANT("tenant"), ORGANIZATION("organization");
        private final String code;
        Scope(String code) { this.code = code; }
        @Override public String getCode() { return code; }
        @Override public String getTitle() { return code; }
    }

    static class ScopedRecord {
        Scope scopeType;
        String tenantId;
        @DiscriminatedValue(discriminator = "scopeType", enumType = Scope.class, cases = {
                @DiscriminatedValueCase(when = "system", source = DiscriminatedValueSource.FIXED, fixedValue = "system"),
                @DiscriminatedValueCase(when = "tenant", source = DiscriminatedValueSource.FIELD, sourceField = "tenantId"),
                @DiscriminatedValueCase(when = "organization", source = DiscriminatedValueSource.REFERENCE,
                        moduleAlias = "iam", entityAlias = "organization",
                        candidateBindings = @ReferenceCandidateBinding(sourceField = "tenantId", targetField = "tenantId"))
        })
        String scopeId;
    }

    static class IncompleteScopedRecord {
        Scope scopeType;
        @DiscriminatedValue(discriminator = "scopeType", enumType = Scope.class, cases = {
                @DiscriminatedValueCase(when = "system", source = DiscriminatedValueSource.FIXED, fixedValue = "system")
        })
        String scopeId;
    }

    static class MultiValueScopedRecord {
        Scope scopeType;
        String tenantId;
        @DiscriminatedValue(discriminator = "scopeType", enumType = Scope.class, cases = {
                @DiscriminatedValueCase(when = "system", source = DiscriminatedValueSource.FIXED, fixedValue = "system"),
                @DiscriminatedValueCase(when = "tenant", source = DiscriminatedValueSource.FIELD, sourceField = "tenantId"),
                @DiscriminatedValueCase(when = "organization", source = DiscriminatedValueSource.REFERENCE,
                        moduleAlias = "iam", entityAlias = "organization", cardinality = ReferenceCardinality.MANY)
        })
        String scopeId;
    }

    static class UnknownDependencyScopedRecord {
        Scope scopeType;
        String tenantId;
        @DiscriminatedValue(discriminator = "scopeType", enumType = Scope.class, cases = {
                @DiscriminatedValueCase(when = "system", source = DiscriminatedValueSource.FIXED, fixedValue = "system"),
                @DiscriminatedValueCase(when = "tenant", source = DiscriminatedValueSource.FIELD, sourceField = "tenantId"),
                @DiscriminatedValueCase(when = "organization", source = DiscriminatedValueSource.REFERENCE,
                        moduleAlias = "iam", entityAlias = "organization",
                        candidateBindings = @ReferenceCandidateBinding(sourceField = "missingTenantId", targetField = "tenantId"))
        })
        String scopeId;
    }
}
