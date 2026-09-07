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
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldService;
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
    private final ObjectProvider<PlatformPageDefinitionService> pages;
    private final ObjectProvider<PlatformPresentationVariantService> variants;
    private final ObjectProvider<PlatformPresentationRevisionService> revisions;

    public PresentationConfigurationReferences(ObjectProvider<MetadataFieldService> fields,
            ObjectProvider<ModuleMetadataFieldService> moduleFields,
            ObjectProvider<ModuleMetadataRelationService> relations,
            ObjectProvider<PlatformPageDefinitionService> pages,
            ObjectProvider<PlatformPresentationVariantService> variants,
            ObjectProvider<PlatformPresentationRevisionService> revisions) {
        this.fields = fields;
        this.moduleFields = moduleFields;
        this.relations = relations;
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
        List<ModuleMetadataRelation> scopes;
        if (target == ConfigurationReferenceTarget.MODULE_METADATA_RELATION) {
            ModuleMetadataRelation relation = relations.getObject().select(id);
            scopes = relation == null ? List.of() : List.of(relation);
        } else if (target == ConfigurationReferenceTarget.MODULE_METADATA_FIELD) {
            ModuleMetadataField moduleField = moduleFields.getObject().select(id);
            if (moduleField == null) return Optional.empty();
            field = fields.getObject().select(moduleField.getMetadataFieldId());
            ModuleMetadataRelation relation = relations.getObject().select(moduleField.getRelationId());
            scopes = relation == null ? List.of() : List.of(relation);
        } else {
            if (target == ConfigurationReferenceTarget.METADATA_FIELD) {
                field = fields.getObject().select(id);
                if (field == null) return Optional.empty();
            }
            scopes = relations.getObject().list(Criteria.of().eq("metadataId",
                    field == null ? id : field.getMetadataId()), ALL);
        }
        if (target == ConfigurationReferenceTarget.MODULE_METADATA_FIELD && field == null) return Optional.empty();
        for (ModuleMetadataRelation scope : scopes) {
            for (PlatformPageDefinition page : pages.getObject().list(
                    Criteria.of().eq("moduleAlias", scope.getModuleAlias()), ALL)) {
                boolean main = Objects.equals(page.getMainRelationId(), scope.getId());
                if (field == null && main) continue;
                ModuleMetadataRelation pageMain = main ? scope : relations.getObject().select(page.getMainRelationId());
                if (!main && (pageMain == null || !Objects.equals(scope.getParentMetadataId(), pageMain.getMetadataId()))) continue;
                for (PlatformPresentationVariant variant : variants.getObject().list(Criteria.of().eq("pageId", page.getId()), ALL)) {
                    for (PlatformPresentationRevision revision : revisions.getObject().list(Criteria.of().eq("variantId", variant.getId()), ALL)) {
                        if (revision.getStatus() != PlatformPresentationRevisionStatus.DRAFT
                                && revision.getStatus() != PlatformPresentationRevisionStatus.PUBLISHED) continue;
                        if (uses(revision, main, scope.getRelationAlias(), field == null ? null : field.getFieldName())) {
                            return Optional.of(revision.getId());
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    private boolean uses(PlatformPresentationRevision revision, boolean main, String relation, String field) {
        try {
            JsonNode root = JSON.readTree(revision.getUiTreeJson());
            if (root == null) throw new IllegalArgumentException("empty tree");
            if (main && containsField(root.path("quickSearchFields"), field)) return true;
            for (JsonNode slot : root.path("nodes")) {
                if (main) {
                    if (Objects.equals(field, slot.path("titleField").asText(null))
                            || Objects.equals(field, slot.path("secondaryField").asText(null))) return true;
                    if (containsField(slot.path("fields"), field)) return true;
                    for (JsonNode group : slot.path("groups")) {
                        if (containsField(group.path("fields"), field)) return true;
                    }
                } else {
                    for (JsonNode child : slot.path("relations")) {
                        if (Objects.equals(relation, child.path("relation").asText())
                                && (field == null || containsField(child.path("fields"), field))) return true;
                    }
                }
            }
            return false;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new PlatformException("页面修订“" + revision.getTitle() + "”结构无法解析，请修复页面配置后再删除元数据。", exception);
        }
    }

    private boolean containsField(JsonNode entries, String field) {
        for (JsonNode entry : entries) {
            if (Objects.equals(field, entry.isTextual() ? entry.asText() : entry.path("field").asText())) return true;
        }
        return false;
    }
}
