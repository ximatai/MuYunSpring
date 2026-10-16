package net.ximatai.muyun.spring.platform.metadata;

import java.util.Map;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceCandidateField;
import net.ximatai.muyun.spring.ability.reference.ReferenceCandidateKey;
import net.ximatai.muyun.spring.ability.reference.ReferenceTargets;
import net.ximatai.muyun.spring.common.security.FieldProtectionDefinition;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.platform.application.Application;
import net.ximatai.muyun.spring.platform.application.ApplicationService;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Lists only fields that the platform can prove safe for a reference target configuration.
 * This is a metadata directory, never a target-record query.
 */
@Service
public class ReferenceTargetFieldCatalogService {
    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);

    private final ModuleMetadataRelationService relationService;
    private final PlatformModuleService moduleService;
    private final ApplicationService applicationService;
    private final MetadataFieldService fieldService;
    private final MetadataFieldProtectionConfigService protectionService;

    public ReferenceTargetFieldCatalogService(ModuleMetadataRelationService relationService,
                                              PlatformModuleService moduleService,
                                              ApplicationService applicationService,
                                              MetadataFieldService fieldService,
                                              MetadataFieldProtectionConfigService protectionService) {
        this.relationService = relationService;
        this.moduleService = moduleService;
        this.applicationService = applicationService;
        this.fieldService = fieldService;
        this.protectionService = protectionService;
    }

    /** Configuration candidates share the same source scope and target capability as field lookup. */
    public List<TargetModule> modules(String sourceModuleAlias, String sourceRelationId) {
        requireSourceRelation(sourceModuleAlias, sourceRelationId);
        return modules();
    }

    /** Source-independent configuration discovery; callers enforce configuration access. */
    public List<TargetModule> modules() {
        return discoverModules().stream().filter(ModuleCandidate::referenceReady)
                .map(module -> new TargetModule(module.alias(), module.title())).toList();
    }

    /** Discovery retains unavailable objects so callers do not mistake them for missing business models. */
    public List<ModuleCandidate> discoverModules() {
        var applicationTitles = applicationService.list(Criteria.of(), ALL).stream()
                .filter(application -> application.getTitle() != null)
                .collect(java.util.stream.Collectors.toMap(Application::getAlias, Application::getTitle));
        var titledMetadata = fieldService.list(Criteria.of().eq("titleField", true).eq("fieldName", "title"), ALL).stream()
                .map(MetadataField::getMetadataId).collect(java.util.stream.Collectors.toSet());
        var dynamicTargets = relationService.list(Criteria.of().eq("relationRole", RelationRole.MAIN), ALL)
                .stream().filter(relation -> titledMetadata.contains(relation.getMetadataId()))
                .map(ModuleMetadataRelation::getModuleAlias).collect(java.util.stream.Collectors.toSet());
        return moduleService.list(Criteria.of(), ALL).stream().map(module -> {
            boolean ready = Boolean.TRUE.equals(module.getEnabled()) && (module.getModuleKind() == ModuleKind.DYNAMIC ? dynamicTargets.contains(module.getAlias())
                    : PlatformAbilityRuntime.referenceTargetResolver().resolve(ReferenceTargets.fromModuleAlias(module.getAlias())).isPresent());
            return new ModuleCandidate(module.getAlias(), module.getTitle(), module.getApplicationAlias(),
                    applicationTitles.get(module.getApplicationAlias()), module.getModuleKind(), ready,
                    ready ? "可通过标准引用配置复用；业务数据访问仍须授权" : "对象已存在，但尚未具备标准引用能力；不能据此重复创建或自动改造");
        }).sorted(Comparator.comparing(ModuleCandidate::alias)).toList();
    }

    public record ModuleCandidate(String alias, String title, String applicationAlias, String applicationTitle, ModuleKind kind, boolean referenceReady, String explanation) {}

    public record TargetModule(String alias, String title) {}

    private void requireSourceRelation(String sourceModuleAlias, String sourceRelationId) {
        String source = PlatformNameRules.requireModuleAlias(sourceModuleAlias);
        ModuleMetadataRelation relation = relationService.select(sourceRelationId);
        if (relation == null || !source.equals(relation.getModuleAlias())) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.reference-target-field-catalog.metadata-relation-does-not-belong-to-module",
                    "metadata relation does not belong to module: {value1}.{value2}"), Map.of("value1", String.valueOf(source), "value2", String.valueOf(sourceRelationId)));
        }
    }

    public ReferenceTargetFieldCatalog list(String sourceModuleAlias, String sourceRelationId,
                                            String targetModuleAlias, String targetMetadataId) {
        requireSourceRelation(sourceModuleAlias, sourceRelationId);
        return target(targetModuleAlias, targetMetadataId);
    }

    public ReferenceTargetFieldCatalog target(String targetModuleAlias, String targetMetadataId) {
        String targetAlias = PlatformNameRules.requireModuleAlias(targetModuleAlias);
        PlatformModule targetModule = moduleService.select(targetAlias);
        if (targetModule == null || !Boolean.TRUE.equals(targetModule.getEnabled())) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.reference-target-field-catalog.reference-target-module-does-not-exist",
                    "reference target module does not exist: {value1}"), Map.of("value1", String.valueOf(targetAlias)));
        }
        return targetModule.getModuleKind() == ModuleKind.DYNAMIC
                ? dynamicCatalog(targetAlias, targetMetadataId)
                : staticCatalog(targetAlias);
    }

    private ReferenceTargetFieldCatalog dynamicCatalog(String targetModuleAlias, String targetMetadataId) {
        ModuleMetadataRelation main = relationService.list(Criteria.of()
                        .eq("moduleAlias", targetModuleAlias)
                        .eq("relationRole", RelationRole.MAIN), ALL)
                .stream().findFirst().orElseThrow(() -> PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.reference-target-field-catalog.dynamic-reference-target-requires-a-main-metadata-relation",
                        "dynamic reference target requires a main metadata relation: {value1}"), Map.of("value1", String.valueOf(targetModuleAlias))));
        if (targetMetadataId != null && !targetMetadataId.isBlank() && !targetMetadataId.equals(main.getMetadataId())) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.reference-target-field-catalog.reference-target-metadata-is-not-the-target-module-main-entity",
                    "reference target metadata is not the target module main entity: {value1}"), Map.of("value1", String.valueOf(targetMetadataId)));
        }
        var savedFields = fieldService.list(Criteria.of().eq("metadataId", main.getMetadataId()), ALL);
        if (savedFields.stream().noneMatch(field -> "title".equals(field.getFieldName()) && Boolean.TRUE.equals(field.getTitleField())))
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.reference-target-field-catalog.the-reference-target-has-no-standard-name-field-configure-one-first",
                    "引用目标尚无标准名称字段，请先通过字段配置设置名称：{value1}"), Map.of("value1", String.valueOf(targetModuleAlias)));
        List<MetadataField> fields = savedFields.stream()
                .filter(this::readablePhysicalField)
                .sorted(Comparator.comparing(MetadataField::getFieldName))
                .toList();
        List<ReferenceTargetFieldCandidate> keys = new ArrayList<>();
        keys.add(new ReferenceTargetFieldCandidate("id", "ID", true, true));
        fields.stream().filter(field -> Boolean.TRUE.equals(field.getUniqueField()))
                .forEach(field -> keys.add(candidate(field, false)));
        List<ReferenceTargetFieldCandidate> labels = fields.stream()
                .map(field -> candidate(field, Boolean.TRUE.equals(field.getTitleField())
                        || "title".equals(field.getFieldName())))
                .toList();
        return new ReferenceTargetFieldCatalog(targetModuleAlias, main.getMetadataId(), keys, labels);
    }

    private ReferenceTargetFieldCatalog staticCatalog(String targetModuleAlias) {
        ReferenceAbility<?> ability = PlatformAbilityRuntime.referenceTargetResolver()
                .resolve(ReferenceTargets.fromModuleAlias(targetModuleAlias))
                .orElseThrow(() -> PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.reference-target-field-catalog.static-reference-target-is-not-registered",
                        "static reference target is not registered: {value1}"), Map.of("value1", String.valueOf(targetModuleAlias))));
        List<ReferenceTargetFieldCandidate> keys = ability.referenceCandidateKeys().stream()
                .filter(ReferenceCandidateKey::usable)
                .map(candidate -> new ReferenceTargetFieldCandidate(candidate.fieldName(), candidate.fieldName(),
                        "id".equals(candidate.fieldName()), true))
                .toList();
        List<ReferenceTargetFieldCandidate> labels = ability.referenceCandidateLabels().stream()
                .map(candidate -> new ReferenceTargetFieldCandidate(candidate.fieldName(), candidate.fieldName(),
                        candidate.defaultField(), true))
                .toList();
        return new ReferenceTargetFieldCatalog(targetModuleAlias, null, keys, labels);
    }

    private boolean readablePhysicalField(MetadataField field) {
        if (field.getFieldForm() != null && field.getFieldForm() != MetadataFieldForm.PHYSICAL) return false;
        FieldProtectionDefinition protection = protectionService == null
                ? FieldProtectionDefinition.NONE : protectionService.definition(field.getId());
        return protection == null || !protection.hasStorageProtection();
    }

    private static ReferenceTargetFieldCandidate candidate(MetadataField field, boolean defaultField) {
        return new ReferenceTargetFieldCandidate(field.getFieldName(), field.getTitle(), defaultField, true);
    }
}
