package net.ximatai.muyun.spring.platform.ui;

import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.ability.reference.ReferenceCardinality;
import net.ximatai.muyun.spring.ability.reference.ReferencePlan;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import net.ximatai.muyun.spring.ability.reference.ReferenceTargetResolver;
import net.ximatai.muyun.spring.platform.metadata.ConfigurationReferenceContributor;
import net.ximatai.muyun.spring.platform.metadata.Metadata;
import net.ximatai.muyun.spring.platform.metadata.MetadataField;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldReferenceConfig;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldReferenceConfigService;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldService;
import net.ximatai.muyun.spring.platform.metadata.MetadataService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataField;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelation;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelationService;
import net.ximatai.muyun.spring.platform.metadata.RelationRole;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PresentationConfigurationReferencesTest {
    @AfterEach
    void resetRuntime() {
        PlatformAbilityRuntime.resetReferenceTargetResolver();
    }

    @Test
    void protectsTheRootHopAndCrossModuleTerminalByTheirPersistedIdentities() {
        Fixture fixture = new Fixture("supplierId.organizationId.title");

        assertThat(fixture.references.presentationMainRelationReference()
                .findReferenceId("relation-order")).contains("page-1");
        assertReferenced(fixture.fieldReferences(), "field-supplier");
        assertReferenced(fixture.fieldReferences(), "field-organization");
        assertReferenced(fixture.fieldReferences(), "field-organization-title");
        assertReferenced(fixture.moduleFieldReferences(), "module-field-supplier");
        assertReferenced(fixture.moduleFieldReferences(), "module-field-organization");
        assertReferenced(fixture.moduleFieldReferences(), "module-field-organization-title");
        assertReferenced(fixture.relationReferences(), "relation-supplier");
        assertReferenced(fixture.relationReferences(), "relation-organization");
    }

    @Test
    void followsAStaticIntermediateIntoItsDynamicTarget() {
        Fixture fixture = new Fixture("externalSupplierId.organizationId.title");
        PlatformAbilityRuntime.configureReferenceTargetResolver(new ReferenceTargetResolver() {
            @Override
            public Optional<net.ximatai.muyun.spring.ability.reference.ReferenceAbility<?>> resolve(ReferenceTarget target) {
                return Optional.empty();
            }

            @Override
            public Optional<ReferencePlan> referencePlan(ReferenceTarget sourceTarget, String sourceField) {
                return ReferenceTarget.of("partner", "supplier").equals(sourceTarget)
                        && "organizationId".equals(sourceField)
                        ? Optional.of(ReferencePlan.of(sourceField, ReferenceTarget.of("iam.organization", "organization"),
                                ReferenceCardinality.ONE))
                        : Optional.empty();
            }
        });

        assertReferenced(fixture.fieldReferences(), "field-external-supplier");
        assertReferenced(fixture.moduleFieldReferences(), "module-field-external-supplier");
        assertReferenced(fixture.fieldReferences(), "field-organization-title");
        assertReferenced(fixture.moduleFieldReferences(), "module-field-organization-title");
        assertReferenced(fixture.relationReferences(), "relation-organization");
    }

    @Test
    void doesNotProtectAnUnrelatedFieldWithTheSameName() {
        Fixture fixture = new Fixture("supplierId.organizationId.title");

        assertThat(fixture.fieldReferences().findReferenceId("field-unrelated-title")).isEmpty();
    }

    @Test
    void protectsPublishedPointPathReferences() {
        Fixture fixture = new Fixture("supplierId.organizationId.title");
        fixture.revision.setStatus(PlatformPresentationRevisionStatus.PUBLISHED);

        assertReferenced(fixture.fieldReferences(), "field-organization-title");
        assertReferenced(fixture.moduleFieldReferences(), "module-field-organization-title");
    }

    @Test
    void preservesMetadataFieldProtectionForLegacyDirectChildRegions() {
        Fixture fixture = new Fixture("supplierId.organizationId.title");
        fixture.metadata("metadata-line", "line");
        ModuleMetadataRelation child = new ModuleMetadataRelation();
        child.setId("relation-line");
        child.setModuleAlias("purchase.order");
        child.setMetadataId("metadata-line");
        child.setParentMetadataId("metadata-order");
        child.setRelationAlias("line");
        child.setRelationRole(RelationRole.CHILD);
        fixture.relationDao.insert(child);
        fixture.field("field-line-title", "metadata-line", "title");
        fixture.revision.setUiTreeJson("""
                {"nodes":[{"relations":[{"relation":"line","fields":["title"]}]}]}
                """);

        assertReferenced(fixture.fieldReferences(), "field-line-title");
    }

    @Test
    void ignoresArchivedRevisions() {
        Fixture fixture = new Fixture("supplierId.organizationId.title");
        fixture.revision.setStatus(PlatformPresentationRevisionStatus.ARCHIVED);

        assertThat(fixture.fieldReferences().findReferenceId("field-supplier")).isEmpty();
        assertThat(fixture.moduleFieldReferences().findReferenceId("module-field-supplier")).isEmpty();
        assertThat(fixture.relationReferences().findReferenceId("relation-supplier")).isEmpty();
    }

    private static void assertReferenced(ConfigurationReferenceContributor contributor, String targetId) {
        assertThat(contributor.findReferenceId(targetId)).contains("revision-1");
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override public T getObject(Object... args) { return value; }
            @Override public T getIfAvailable() { return value; }
            @Override public T getIfUnique() { return value; }
            @Override public T getObject() { return value; }
        };
    }

    private static final class Fixture {
        private final TestMemoryDao<Metadata> metadataDao = new TestMemoryDao<>();
        private final TestMemoryDao<MetadataField> fieldDao = new TestMemoryDao<>();
        private final TestMemoryDao<ModuleMetadataRelation> relationDao = new TestMemoryDao<>();
        private final TestMemoryDao<ModuleMetadataField> moduleFieldDao = new TestMemoryDao<>();
        private final TestMemoryDao<MetadataFieldReferenceConfig> referenceConfigDao = new TestMemoryDao<>();
        private final TestMemoryDao<PlatformPageDefinition> pageDao = new TestMemoryDao<>();
        private final TestMemoryDao<PlatformPresentationVariant> variantDao = new TestMemoryDao<>();
        private final TestMemoryDao<PlatformPresentationRevision> revisionDao = new TestMemoryDao<>();
        private final MetadataService metadataService = new MetadataService(metadataDao);
        private final MetadataFieldService fieldService = new MetadataFieldService(fieldDao, metadataService, null);
        private final ModuleMetadataRelationService relationService = new ModuleMetadataRelationService(relationDao,
                new PlatformModuleService(new TestMemoryDao<>()), metadataService);
        private final ModuleMetadataFieldService moduleFieldService = new ModuleMetadataFieldService(moduleFieldDao,
                relationService, metadataService, fieldService);
        private final MetadataFieldReferenceConfigService referenceConfigService =
                new MetadataFieldReferenceConfigService(referenceConfigDao, fieldService, metadataService, null,
                        new PlatformModuleService(new TestMemoryDao<>()), relationService);
        private final PlatformPageDefinitionService pageService = new PlatformPageDefinitionService(pageDao,
                new PlatformModuleService(new TestMemoryDao<>()), relationService);
        private final PlatformPresentationVariantService variantService =
                new PlatformPresentationVariantService(variantDao, pageService);
        private final PlatformPresentationRevisionService revisionService =
                new PlatformPresentationRevisionService(revisionDao, variantService);
        private final PresentationConfigurationReferences references = new PresentationConfigurationReferences(
                provider(metadataService), provider(fieldService), provider(moduleFieldService), provider(relationService),
                provider(referenceConfigService), provider(pageService), provider(variantService), provider(revisionService));
        private final PlatformPresentationRevision revision;

        private Fixture(String path) {
            metadata("metadata-order", "order");
            metadata("metadata-supplier", "supplier");
            metadata("metadata-organization", "organization");
            metadata("metadata-unrelated", "unrelated");
            relation("relation-order", "purchase.order", "metadata-order");
            relation("relation-supplier", "supply.supplier", "metadata-supplier");
            relation("relation-organization", "iam.organization", "metadata-organization");
            relation("relation-unrelated", "other.unrelated", "metadata-unrelated");

            field("field-supplier", "metadata-order", "supplierId");
            field("field-external-supplier", "metadata-order", "externalSupplierId");
            field("field-organization", "metadata-supplier", "organizationId");
            field("field-organization-title", "metadata-organization", "title");
            field("field-unrelated-title", "metadata-unrelated", "title");
            moduleField("module-field-supplier", "relation-order", "field-supplier");
            moduleField("module-field-external-supplier", "relation-order", "field-external-supplier");
            moduleField("module-field-organization", "relation-supplier", "field-organization");
            moduleField("module-field-organization-title", "relation-organization", "field-organization-title");
            reference("reference-supplier", "field-supplier", "relation-order", "supply.supplier", "metadata-supplier");
            reference("reference-organization", "field-organization", "relation-supplier", "iam.organization",
                    "metadata-organization");
            reference("reference-external-supplier", "field-external-supplier", "relation-order",
                    "partner.supplier", null);

            PlatformPageDefinition page = new PlatformPageDefinition();
            page.setId("page-1");
            page.setModuleAlias("purchase.order");
            page.setMainRelationId("relation-order");
            pageDao.insert(page);
            PlatformPresentationVariant variant = new PlatformPresentationVariant();
            variant.setId("variant-1");
            variant.setPageId("page-1");
            variantDao.insert(variant);
            revision = new PlatformPresentationRevision();
            revision.setId("revision-1");
            revision.setVariantId("variant-1");
            revision.setRevisionNo(1);
            revision.setStatus(PlatformPresentationRevisionStatus.DRAFT);
            revision.setUiTreeJson("{\"nodes\":[{\"fields\":[\"" + path + "\"]}]}");
            revisionDao.insert(revision);
        }

        private ConfigurationReferenceContributor fieldReferences() {
            return references.presentationFieldReference();
        }

        private ConfigurationReferenceContributor moduleFieldReferences() {
            return references.presentationModuleFieldReference();
        }

        private ConfigurationReferenceContributor relationReferences() {
            return references.presentationRelationReference();
        }

        private void metadata(String id, String alias) {
            Metadata value = new Metadata();
            value.setId(id);
            value.setAlias(alias);
            metadataDao.insert(value);
        }

        private void relation(String id, String moduleAlias, String metadataId) {
            ModuleMetadataRelation value = new ModuleMetadataRelation();
            value.setId(id);
            value.setModuleAlias(moduleAlias);
            value.setMetadataId(metadataId);
            value.setRelationAlias(moduleAlias.substring(moduleAlias.indexOf('.') + 1));
            value.setRelationRole(RelationRole.MAIN);
            relationDao.insert(value);
        }

        private void field(String id, String metadataId, String name) {
            MetadataField value = new MetadataField();
            value.setId(id);
            value.setMetadataId(metadataId);
            value.setFieldName(name);
            fieldDao.insert(value);
        }

        private void moduleField(String id, String relationId, String fieldId) {
            ModuleMetadataField value = new ModuleMetadataField();
            value.setId(id);
            value.setRelationId(relationId);
            value.setMetadataFieldId(fieldId);
            moduleFieldDao.insert(value);
        }

        private void reference(String id, String fieldId, String relationId, String targetModuleAlias,
                               String targetMetadataId) {
            MetadataFieldReferenceConfig value = new MetadataFieldReferenceConfig();
            value.setId(id);
            value.setMetadataFieldId(fieldId);
            value.setRelationId(relationId);
            value.setTargetModuleAlias(targetModuleAlias);
            value.setTargetMetadataId(targetMetadataId);
            referenceConfigDao.insert(value);
        }
    }
}
