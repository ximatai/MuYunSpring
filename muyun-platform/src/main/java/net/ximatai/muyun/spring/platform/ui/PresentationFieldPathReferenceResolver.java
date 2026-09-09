package net.ximatai.muyun.spring.platform.ui;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.ability.reference.ReferencePlan;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import net.ximatai.muyun.spring.ability.reference.ReferenceTargets;
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
import org.springframework.beans.factory.ObjectProvider;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves one persisted page field path to its configuration identities.
 *
 * <p>The resolver deliberately starts from a page's persisted main relation and consumes field
 * reference declarations before consulting the runtime. This keeps deletion governance available
 * while a dynamic runtime is stale or temporarily unavailable. Runtime plans remain necessary for
 * a static intermediate entity, because its outgoing fields are Java declarations rather than
 * metadata rows.</p>
 */
final class PresentationFieldPathReferenceResolver {
    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);

    private final ObjectProvider<MetadataService> metadata;
    private final ObjectProvider<MetadataFieldService> fields;
    private final ObjectProvider<ModuleMetadataFieldService> moduleFields;
    private final ObjectProvider<ModuleMetadataRelationService> relations;
    private final ObjectProvider<MetadataFieldReferenceConfigService> referenceConfigs;

    PresentationFieldPathReferenceResolver(ObjectProvider<MetadataService> metadata,
                                           ObjectProvider<MetadataFieldService> fields,
                                           ObjectProvider<ModuleMetadataFieldService> moduleFields,
                                           ObjectProvider<ModuleMetadataRelationService> relations,
                                           ObjectProvider<MetadataFieldReferenceConfigService> referenceConfigs) {
        this.metadata = metadata;
        this.fields = fields;
        this.moduleFields = moduleFields;
        this.relations = relations;
        this.referenceConfigs = referenceConfigs;
    }

    boolean uses(ModuleMetadataRelation source, String fieldPath, String metadataFieldId,
                 String moduleMetadataFieldId, String relationId) {
        if (source == null || fieldPath == null || fieldPath.isBlank()) return false;
        PathReferences references = resolve(source, fieldPath);
        return metadataFieldId != null && references.metadataFieldIds().contains(metadataFieldId)
                || moduleMetadataFieldId != null && references.moduleMetadataFieldIds().contains(moduleMetadataFieldId)
                || relationId != null && references.relationIds().contains(relationId);
    }

    private PathReferences resolve(ModuleMetadataRelation source, String fieldPath) {
        List<String> segments = List.of(fieldPath.split("\\.", -1));
        if (segments.isEmpty() || segments.stream().anyMatch(segment -> segment.isBlank())) {
            return PathReferences.EMPTY;
        }
        LinkedHashSet<String> metadataFieldIds = new LinkedHashSet<>();
        LinkedHashSet<String> moduleMetadataFieldIds = new LinkedHashSet<>();
        LinkedHashSet<String> relationIds = new LinkedHashSet<>();
        Node node = Node.dynamic(source);
        for (int index = 0; index < segments.size(); index++) {
            String segment = segments.get(index);
            if (node.relation() == null) {
                Optional<ReferencePlan> plan = PlatformAbilityRuntime.referenceTargetResolver()
                        .referencePlan(node.staticTarget(), segment);
                if (plan.isEmpty()) break;
                node = nodeFor(plan.orElseThrow().target());
                continue;
            }
            MetadataField field = field(node.relation().getMetadataId(), segment);
            if (field == null) break;
            metadataFieldIds.add(field.getId());
            relationIds.add(node.relation().getId());
            ModuleMetadataField moduleField = moduleField(node.relation().getId(), field.getId());
            if (moduleField != null) moduleMetadataFieldIds.add(moduleField.getId());
            if (index == segments.size() - 1) continue;
            node = next(node.relation(), field, moduleField);
            if (node == null) break;
        }
        return new PathReferences(metadataFieldIds, moduleMetadataFieldIds, relationIds);
    }

    private Node next(ModuleMetadataRelation source, MetadataField field, ModuleMetadataField moduleField) {
        if (moduleField != null && hasText(moduleField.getReferenceModuleAlias())) {
            return mainRelation(moduleField.getReferenceModuleAlias()).map(Node::dynamic)
                    .orElseGet(() -> Node.staticTarget(ReferenceTargets.fromModuleAlias(
                            moduleField.getReferenceModuleAlias())));
        }
        MetadataFieldReferenceConfigService configService = referenceConfigs.getIfAvailable();
        MetadataFieldReferenceConfig config = configService == null ? null
                : configService.findForRelation(field.getId(), source.getId());
        if (config != null && hasText(config.getTargetMetadataId())) {
            String targetModule = hasText(config.getTargetModuleAlias())
                    ? config.getTargetModuleAlias() : source.getModuleAlias();
            return relation(targetModule, config.getTargetMetadataId()).map(Node::dynamic).orElse(null);
        }
        if (config != null && hasText(config.getTargetModuleAlias())) {
            return Node.staticTarget(ReferenceTargets.fromModuleAlias(config.getTargetModuleAlias()));
        }
        ReferenceTarget sourceTarget = sourceTarget(source);
        if (sourceTarget == null) return null;
        return PlatformAbilityRuntime.referenceTargetResolver().referencePlan(sourceTarget, field.getFieldName())
                .map(ReferencePlan::target).map(this::nodeFor).orElse(null);
    }

    private Node nodeFor(ReferenceTarget target) {
        return dynamicRelation(target).map(Node::dynamic).orElseGet(() -> Node.staticTarget(target));
    }

    private ReferenceTarget sourceTarget(ModuleMetadataRelation relation) {
        MetadataService metadataService = metadata.getIfAvailable();
        Metadata sourceMetadata = metadataService == null ? null : metadataService.select(relation.getMetadataId());
        return sourceMetadata == null ? null : ReferenceTarget.of(relation.getModuleAlias(), sourceMetadata.getAlias());
    }

    private Optional<ModuleMetadataRelation> dynamicRelation(ReferenceTarget target) {
        ModuleMetadataRelationService relationService = relations.getIfAvailable();
        MetadataService metadataService = metadata.getIfAvailable();
        if (relationService == null || metadataService == null) return Optional.empty();
        return relationService.list(Criteria.of().eq("moduleAlias", target.moduleAlias())
                        .eq("relationRole", RelationRole.MAIN), ALL).stream()
                .filter(relation -> {
                    Metadata candidate = metadataService.select(relation.getMetadataId());
                    return candidate != null && target.entityAlias().equals(candidate.getAlias());
                })
                .findFirst();
    }

    private Optional<ModuleMetadataRelation> mainRelation(String moduleAlias) {
        ModuleMetadataRelationService relationService = relations.getIfAvailable();
        if (relationService == null) return Optional.empty();
        return relationService.list(Criteria.of().eq("moduleAlias", moduleAlias)
                        .eq("relationRole", RelationRole.MAIN), ALL).stream().findFirst();
    }

    private Optional<ModuleMetadataRelation> relation(String moduleAlias, String metadataId) {
        ModuleMetadataRelationService relationService = relations.getIfAvailable();
        if (relationService == null) return Optional.empty();
        return relationService.list(Criteria.of().eq("moduleAlias", moduleAlias)
                        .eq("metadataId", metadataId), ALL).stream()
                .sorted(java.util.Comparator.comparing(relation -> relation.getRelationRole() != RelationRole.MAIN))
                .findFirst();
    }

    private MetadataField field(String metadataId, String fieldName) {
        MetadataFieldService fieldService = fields.getIfAvailable();
        if (fieldService == null) return null;
        return fieldService.list(Criteria.of().eq("metadataId", metadataId).eq("fieldName", fieldName), ALL)
                .stream().findFirst().orElse(null);
    }

    private ModuleMetadataField moduleField(String relationId, String metadataFieldId) {
        ModuleMetadataFieldService moduleFieldService = moduleFields.getIfAvailable();
        if (moduleFieldService == null) return null;
        return moduleFieldService.listByRelationId(relationId).stream()
                .filter(candidate -> metadataFieldId.equals(candidate.getMetadataFieldId())).findFirst().orElse(null);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record Node(ModuleMetadataRelation relation, ReferenceTarget staticTarget) {
        static Node dynamic(ModuleMetadataRelation relation) { return new Node(relation, null); }
        static Node staticTarget(ReferenceTarget target) { return new Node(null, target); }
    }

    private record PathReferences(Set<String> metadataFieldIds, Set<String> moduleMetadataFieldIds,
                                  Set<String> relationIds) {
        private static final PathReferences EMPTY = new PathReferences(Set.of(), Set.of(), Set.of());
        private PathReferences {
            metadataFieldIds = Set.copyOf(metadataFieldIds);
            moduleMetadataFieldIds = Set.copyOf(moduleMetadataFieldIds);
            relationIds = Set.copyOf(relationIds);
        }
    }
}
