package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.spring.ability.discriminator.DiscriminatedValue;
import net.ximatai.muyun.spring.ability.discriminator.DiscriminatedValueCase;
import net.ximatai.muyun.spring.ability.discriminator.DiscriminatedValueSource;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceCandidateBinding;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.common.model.standard.StandardTitledEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiscriminatedCrudAbilityTest {
    @AfterEach
    void tearDown() {
        PlatformAbilityRuntime.resetReferenceTargetResolver();
    }

    @Test
    void shouldNormalizeFixedAndFieldBranchesThroughStaticCrudInsertAndUpdate() {
        ScopedRecordService service = new ScopedRecordService();
        ScopedRecord record = new ScopedRecord();
        record.setScopeType(Scope.SYSTEM);
        record.setTenantId("tenant-a");
        record.setScopeId("ignored");

        service.insert(record);

        assertThat(record.getScopeId()).isEqualTo("system");
        record.setScopeType(Scope.TENANT);
        record.setScopeId("ignored-again");
        assertThat(service.update(record)).isEqualTo(1);
        assertThat(record.getScopeId()).isEqualTo("tenant-a");
    }

    @Test
    void shouldValidateReferenceBranchDependenciesThroughStaticCrudWrite() {
        OrganizationReferenceService organization = new OrganizationReferenceService();
        OrganizationRecord organizationRecord = new OrganizationRecord("组织 A", "CN");
        organizationRecord.setId("org-a");
        organizationRecord.setTenantId("tenant-a");
        organization.insert(organizationRecord);
        PlatformAbilityRuntime.configureReferenceTargetResolver(referenceTarget ->
                "iam.organization".equals(referenceTarget.qualifiedName()) ? Optional.of(organization) : Optional.empty());
        ScopedRecordService service = new ScopedRecordService();
        ScopedRecord record = new ScopedRecord();
        record.setScopeType(Scope.ORGANIZATION);
        record.setTenantId("tenant-a");
        record.setRegionCode("CN");
        record.setScopeId("org-a");

        service.insert(record);

        record.setRegionCode("US");
        assertThatThrownBy(() -> service.update(record))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("reference target does not satisfy dependency: regionCode");
    }

    private enum Scope implements CodeTitleEnum {
        SYSTEM("system"), TENANT("tenant"), ORGANIZATION("organization");

        private final String code;

        Scope(String code) {
            this.code = code;
        }

        @Override
        public String getCode() {
            return code;
        }

        @Override
        public String getTitle() {
            return code;
        }
    }

    private static final class ScopedRecord extends StandardEntity {
        private Scope scopeType;
        private String regionCode;

        @DiscriminatedValue(discriminator = "scopeType", enumType = Scope.class, cases = {
                @DiscriminatedValueCase(when = "system", source = DiscriminatedValueSource.FIXED, fixedValue = "system"),
                @DiscriminatedValueCase(when = "tenant", source = DiscriminatedValueSource.FIELD, sourceField = "tenantId"),
                @DiscriminatedValueCase(when = "organization", source = DiscriminatedValueSource.REFERENCE,
                        moduleAlias = "iam", entityAlias = "organization",
                        candidateBindings = @ReferenceCandidateBinding(sourceField = "regionCode", targetField = "regionCode"))
        })
        private String scopeId;

        Scope getScopeType() {
            return scopeType;
        }

        void setScopeType(Scope scopeType) {
            this.scopeType = scopeType;
        }

        String getRegionCode() {
            return regionCode;
        }

        void setRegionCode(String regionCode) {
            this.regionCode = regionCode;
        }

        String getScopeId() {
            return scopeId;
        }

        void setScopeId(String scopeId) {
            this.scopeId = scopeId;
        }
    }

    private static final class ScopedRecordService implements CrudAbility<ScopedRecord> {
        private final InMemoryBaseDao<ScopedRecord> dao = new InMemoryBaseDao<>();

        @Override
        public BaseDao<ScopedRecord, String> getDao() {
            return dao;
        }

        @Override
        public String getModuleAlias() {
            return "test.scoped_record";
        }
    }

    private static final class OrganizationRecord extends StandardTitledEntity {
        private String regionCode;

        OrganizationRecord(String title, String regionCode) {
            setTitle(title);
            this.regionCode = regionCode;
        }

        String getRegionCode() {
            return regionCode;
        }
    }

    private static final class OrganizationReferenceService extends AbstractAbilityService<OrganizationRecord>
            implements ReferenceAbility<OrganizationRecord> {
        OrganizationReferenceService() {
            super("iam.organization", OrganizationRecord.class, new InMemoryBaseDao<>());
        }
    }
}
