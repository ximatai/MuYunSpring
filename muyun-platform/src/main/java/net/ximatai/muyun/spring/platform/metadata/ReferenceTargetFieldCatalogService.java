package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceCandidateField;
import net.ximatai.muyun.spring.ability.reference.ReferenceCandidateKey;
import net.ximatai.muyun.spring.ability.reference.ReferenceTargets;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.security.FieldProtectionDefinition;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
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
    private final MetadataFieldService fieldService;
    private final MetadataFieldProtectionConfigService protectionService;

    public ReferenceTargetFieldCatalogService(ModuleMetadataRelationService relationService,
                                              PlatformModuleService moduleService,
                                              MetadataFieldService fieldService,
                                              MetadataFieldProtectionConfigService protectionService) {
        this.relationService = relationService;
        this.moduleService = moduleService;
        this.fieldService = fieldService;
        this.protectionService = protectionService;
    }

    public ReferenceTargetFieldCatalog list(String sourceModuleAlias, String sourceRelationId,
                                            String targetModuleAlias, String targetMetadataId) {
        String source = PlatformNameRules.requireModuleAlias(sourceModuleAlias);
        ModuleMetadataRelation relation = relationService.select(sourceRelationId);
        if (relation == null || !source.equals(relation.getModuleAlias())) {
            throw new PlatformException("metadata relation does not belong to module: " + source + "." + sourceRelationId);
        }
        String targetAlias = PlatformNameRules.requireModuleAlias(targetModuleAlias);
        PlatformModule targetModule = moduleService.select(targetAlias);
        if (targetModule == null) {
            throw new PlatformException("reference target module does not exist: " + targetAlias);
        }
        return targetModule.getModuleKind() == ModuleKind.DYNAMIC
                ? dynamicCatalog(targetAlias, targetMetadataId)
                : staticCatalog(targetAlias);
    }

    private ReferenceTargetFieldCatalog dynamicCatalog(String targetModuleAlias, String targetMetadataId) {
        ModuleMetadataRelation main = relationService.list(Criteria.of()
                        .eq("moduleAlias", targetModuleAlias)
                        .eq("relationRole", RelationRole.MAIN), ALL)
                .stream().findFirst().orElseThrow(() -> new PlatformException(
                        "dynamic reference target requires a main metadata relation: " + targetModuleAlias));
        if (targetMetadataId != null && !targetMetadataId.isBlank() && !targetMetadataId.equals(main.getMetadataId())) {
            throw new PlatformException("reference target metadata is not the target module main entity: " + targetMetadataId);
        }
        List<MetadataField> fields = fieldService.list(Criteria.of().eq("metadataId", main.getMetadataId()), ALL).stream()
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
                .orElseThrow(() -> new PlatformException("static reference target is not registered: " + targetModuleAlias));
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
