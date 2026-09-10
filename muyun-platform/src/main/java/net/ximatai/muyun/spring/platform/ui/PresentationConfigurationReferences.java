package net.ximatai.muyun.spring.platform.ui;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.platform.metadata.ConfigurationReference;
import net.ximatai.muyun.spring.platform.metadata.ConfigurationReferenceContributor;
import net.ximatai.muyun.spring.platform.metadata.ConfigurationReferenceTarget;
import net.ximatai.muyun.spring.platform.metadata.MetadataField;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldReferenceConfigService;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldService;
import net.ximatai.muyun.spring.platform.metadata.MetadataService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataField;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelation;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelationService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Page-owned dependencies join the same deletion guard as structured configuration records. */
@Configuration
public class PresentationConfigurationReferences {
    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ObjectProvider<MetadataFieldService> fields;
    private final ObjectProvider<ModuleMetadataFieldService> moduleFields;
    private final ObjectProvider<ModuleMetadataRelationService> relations;
    private final PresentationFieldPathReferenceResolver pathReferences;
    private final ObjectProvider<PlatformPageDefinitionService> pages;
    private final ObjectProvider<PlatformPresentationVariantService> variants;
    private final ObjectProvider<PlatformPresentationRevisionService> revisions;

    public PresentationConfigurationReferences(ObjectProvider<MetadataService> metadata,
            ObjectProvider<MetadataFieldService> fields,
            ObjectProvider<ModuleMetadataFieldService> moduleFields,
            ObjectProvider<ModuleMetadataRelationService> relations,
            ObjectProvider<MetadataFieldReferenceConfigService> referenceConfigs,
            ObjectProvider<PlatformPageDefinitionService> pages,
            ObjectProvider<PlatformPresentationVariantService> variants,
            ObjectProvider<PlatformPresentationRevisionService> revisions) {
        this.fields = fields;
        this.moduleFields = moduleFields;
        this.relations = relations;
        this.pathReferences = new PresentationFieldPathReferenceResolver(metadata, fields, moduleFields, relations,
                referenceConfigs);
        this.pages = pages;
        this.variants = variants;
        this.revisions = revisions;
    }

    @Bean ConfigurationReferenceContributor presentationFieldReference() {
        return contributor(ConfigurationReferenceTarget.METADATA_FIELD);
    }

    @Bean ConfigurationReferenceContributor presentationModuleFieldReference() {
        return contributor(ConfigurationReferenceTarget.MODULE_METADATA_FIELD);
    }

    @Bean ConfigurationReferenceContributor presentationRelationReference() {
        return contributor(ConfigurationReferenceTarget.MODULE_METADATA_RELATION);
    }

    @Bean ConfigurationReferenceContributor presentationMainRelationReference() {
        return new ConfigurationReferenceContributor() {
            public ConfigurationReferenceTarget target() { return ConfigurationReferenceTarget.MODULE_METADATA_RELATION; }
            public ConfigurationReference reference() {
                return new ConfigurationReference("pageDefinition", "页面主实体绑定", "mainRelationId");
            }
            public Optional<String> findReferenceId(String id) {
                return pages.getObject().list(Criteria.of().eq("mainRelationId", id), PageRequest.of(1, 1))
                        .stream().map(PlatformPageDefinition::getId).findFirst();
            }
            public String describeReference(String id) { return "页面“" + pageTitle(id) + "”的主实体绑定"; }
        };
    }

    private ConfigurationReferenceContributor contributor(ConfigurationReferenceTarget target) {
        return new ConfigurationReferenceContributor() {
            public ConfigurationReferenceTarget target() { return target; }
            public ConfigurationReference reference() {
                return new ConfigurationReference("presentationRevision", "页面配置引用", "uiTreeJson");
            }
            public Optional<String> findReferenceId(String id) { return find(target, id); }
            public String describeReference(String id) {
                PlatformPresentationRevision revision = revisions.getObject().select(id);
                if (revision == null) return reference().resourceName();
                PlatformPresentationVariant variant = variants.getObject().select(revision.getVariantId());
                String title = variant == null ? revision.getVariantId() : pageTitle(variant.getPageId());
                return "页面“" + title + "”的"
                        + (revision.getStatus() == PlatformPresentationRevisionStatus.PUBLISHED ? "已发布" : "草稿")
                        + "修订 v" + revision.getRevisionNo() + " 引用；请先在页面配置中移除引用，已发布引用还需重新发布";
            }
        };
    }

    private String pageTitle(String id) {
        PlatformPageDefinition page = pages.getObject().select(id);
        return page == null ? id : page.getModuleAlias() + " / " + page.getTitle();
    }

    private Optional<String> find(ConfigurationReferenceTarget target, String id) {
        MetadataField field = null;
        List<ModuleMetadataRelation> targetRelations = List.of();
        if (target == ConfigurationReferenceTarget.MODULE_METADATA_RELATION) {
            ModuleMetadataRelation targetRelation = relations.getObject().select(id);
            if (targetRelation == null) return Optional.empty();
            targetRelations = List.of(targetRelation);
        } else if (target == ConfigurationReferenceTarget.MODULE_METADATA_FIELD) {
            ModuleMetadataField moduleField = moduleFields.getObject().select(id);
            if (moduleField == null) return Optional.empty();
            field = fields.getObject().select(moduleField.getMetadataFieldId());
            ModuleMetadataRelation targetRelation = relations.getObject().select(moduleField.getRelationId());
            if (targetRelation == null) return Optional.empty();
            targetRelations = List.of(targetRelation);
        } else {
            field = fields.getObject().select(id);
            if (field == null) return Optional.empty();
            targetRelations = relations.getObject().list(Criteria.of().eq("metadataId", field.getMetadataId()), ALL);
        }
        for (PlatformPageDefinition page : pages.getObject().list(Criteria.of(), ALL)) {
            ModuleMetadataRelation pageMain = relations.getObject().select(page.getMainRelationId());
            if (pageMain == null) continue;
            for (PlatformPresentationVariant variant : variants.getObject().list(Criteria.of().eq("pageId", page.getId()), ALL)) {
                for (PlatformPresentationRevision revision : revisions.getObject().list(Criteria.of().eq("variantId", variant.getId()), ALL)) {
                    if (revision.getStatus() != PlatformPresentationRevisionStatus.DRAFT
                            && revision.getStatus() != PlatformPresentationRevisionStatus.PUBLISHED) continue;
                    if (uses(revision, pageMain, target, id, field, targetRelations)) {
                        return Optional.of(revision.getId());
                    }
                }
            }
        }
        return Optional.empty();
    }

    private boolean uses(PlatformPresentationRevision revision, ModuleMetadataRelation pageMain,
                         ConfigurationReferenceTarget target, String targetId, MetadataField targetField,
                         List<ModuleMetadataRelation> targetRelations) {
        try {
            JsonNode root = JSON.readTree(revision.getUiTreeJson());
            if (root == null) throw new IllegalArgumentException("empty tree");
            if (containsReference(root.path("quickSearchFields"), pageMain, target, targetId)) return true;
            if (containsSummaryReference(root.path("querySummaries"), pageMain, target, targetId)) return true;
            for (JsonNode slot : root.path("nodes")) {
                if (usesPath(slot.path("titleField").asText(null), pageMain, target, targetId)
                        || usesPath(slot.path("secondaryField").asText(null), pageMain, target, targetId)
                        || containsReference(slot.path("fields"), pageMain, target, targetId)) return true;
                for (JsonNode group : slot.path("groups")) {
                    if (containsReference(group.path("fields"), pageMain, target, targetId)) return true;
                }
                if (usesLegacyChild(slot.path("relations"), pageMain, target, targetField, targetRelations)) {
                    return true;
                }
            }
            return false;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new PlatformException("页面修订“" + revision.getTitle() + "”结构无法解析，请修复页面配置后再删除元数据。", exception);
        }
    }

    private boolean containsSummaryReference(JsonNode entries, ModuleMetadataRelation pageMain,
                                             ConfigurationReferenceTarget target, String targetId) {
        for (JsonNode entry : entries) {
            if (usesPath(entry.path("fieldName").asText(null), pageMain, target, targetId)) return true;
            if (usesPath(entry.path("groupByField").asText(null), pageMain, target, targetId)) return true;
        }
        return false;
    }

    private boolean containsReference(JsonNode entries, ModuleMetadataRelation pageMain,
                                      ConfigurationReferenceTarget target, String targetId) {
        for (JsonNode entry : entries) {
            String path = entry.isTextual() ? entry.asText() : entry.path("field").asText();
            if (usesPath(path, pageMain, target, targetId)) return true;
        }
        return false;
    }

    private boolean usesPath(String path, ModuleMetadataRelation pageMain,
                             ConfigurationReferenceTarget target, String targetId) {
        return pathReferences.uses(pageMain, path,
                target == ConfigurationReferenceTarget.METADATA_FIELD ? targetId : null,
                target == ConfigurationReferenceTarget.MODULE_METADATA_FIELD ? targetId : null,
                target == ConfigurationReferenceTarget.MODULE_METADATA_RELATION ? targetId : null);
    }

    private boolean usesLegacyChild(JsonNode entries, ModuleMetadataRelation pageMain,
                                    ConfigurationReferenceTarget target, MetadataField targetField,
                                    List<ModuleMetadataRelation> targetRelations) {
        for (ModuleMetadataRelation targetRelation : targetRelations) {
            if (!Objects.equals(targetRelation.getModuleAlias(), pageMain.getModuleAlias())
                    || !Objects.equals(targetRelation.getParentMetadataId(), pageMain.getMetadataId())) continue;
            for (JsonNode child : entries) {
                if (!Objects.equals(targetRelation.getRelationAlias(), child.path("relation").asText())) continue;
                if (target == ConfigurationReferenceTarget.MODULE_METADATA_RELATION) return true;
                if (targetField != null && containsField(child.path("fields"), targetField.getFieldName())) return true;
            }
        }
        return false;
    }

    private boolean containsField(JsonNode entries, String field) {
        for (JsonNode entry : entries) {
            if (Objects.equals(field, entry.isTextual() ? entry.asText() : entry.path("field").asText())) return true;
        }
        return false;
    }
}
