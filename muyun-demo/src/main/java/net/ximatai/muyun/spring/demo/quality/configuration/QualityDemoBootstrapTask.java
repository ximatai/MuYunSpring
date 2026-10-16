package net.ximatai.muyun.spring.demo.quality.configuration;

import org.springframework.context.annotation.Profile;

import net.ximatai.muyun.database.core.orm.*;
import net.ximatai.muyun.spring.ability.PlatformManagedMutationContext;
import net.ximatai.muyun.spring.common.content.ContentPolicy;
import net.ximatai.muyun.spring.common.identity.*;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.demo.quality.equipment.EquipmentCategoryService;
import net.ximatai.muyun.spring.demo.quality.product.ProductService;
import net.ximatai.muyun.spring.platform.menu.*;
import net.ximatai.muyun.spring.platform.metadata.*;
import net.ximatai.muyun.spring.platform.module.*;
import net.ximatai.muyun.spring.platform.runtime.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;

/** Installs one data-declared aggregate, never business translations or per-page translation code. */
@Profile("collaboration-demo")
@Component
public class QualityDemoBootstrapTask implements PlatformBootstrapTask {
    public static final String MODULE_ALIAS = "quality.exception";
    private static final PageRequest ONE = PageRequest.of(1, 1);
    private final PlatformModuleService modules;
    private final MenuService menus;
    private final MetadataService metadata;
    private final MetadataFieldService fields;
    private final ModuleMetadataRelationService relations;
    private final MetadataFieldReferenceConfigService references;
    private final DynamicModuleStandardActionRegistrar actions;
    private final PlatformDynamicRuntimeRefreshService runtime;
    private final TransactionTemplate transactions;

    public QualityDemoBootstrapTask(PlatformModuleService modules, MenuService menus, MetadataService metadata,
            MetadataFieldService fields, ModuleMetadataRelationService relations,
            MetadataFieldReferenceConfigService references, DynamicModuleStandardActionRegistrar actions,
            PlatformDynamicRuntimeRefreshService runtime, TransactionTemplate transactions) {
        this.modules = modules; this.menus = menus; this.metadata = metadata; this.fields = fields;
        this.relations = relations; this.references = references; this.actions = actions;
        this.runtime = runtime; this.transactions = transactions;
    }
    @Override public String name() { return "demo-quality-collaboration"; }
    @Override public int order() { return 210; }
    @Override public void run() {
        try (var actor = CurrentUserContext.use(CurrentUser.systemUser("quality-demo-bootstrap", "Quality Demo Bootstrap"));
             var tenant = TenantContext.system("configure quality collaboration sample")) {
            PlatformManagedMutationContext.runAsPlatformManaged(() -> {
                transactions.executeWithoutResult(status -> configure());
                actions.register(modules.select(MODULE_ALIAS));
                runtime.refresh(MODULE_ALIAS);
                mount();
            });
        }
    }

    private void configure() {
        if (modules.select(MODULE_ALIAS) == null) {
            var module = new PlatformModule();
            module.setAlias(MODULE_ALIAS); module.setApplicationAlias("quality");
            module.setModuleKind(ModuleKind.DYNAMIC); module.setTitle("质量异常单");
            modules.insert(module);
        }
        var main = entity("quality_exception", "质量异常单", true);
        field(main, "title", "title", "string", "异常标题", true, true, ContentPolicy.AUTO_TRANSLATE);
        field(main, "description", "description", "text", "异常说明", true, false, ContentPolicy.AUTO_TRANSLATE);
        var product = field(main, "productId", "product_id", "string", "商品", true, false, ContentPolicy.KEEP_ORIGINAL);
        // A restricted field is intentionally absent from the ordinary page's source boundary.
        field(main, "internalNote", "internal_note", "text", "内部记录", false, false, ContentPolicy.AUTO_TRANSLATE);
        var owner = relation(main, "exception", "质量异常单", null);
        reference(product, owner);

        var inspection = entity("quality_inspection", "检测明细", false);
        field(inspection, "exceptionId", "exception_id", "string", "异常单", true, false, ContentPolicy.KEEP_ORIGINAL);
        field(inspection, "description", "description", "text", "检测说明", true, false, ContentPolicy.AUTO_TRANSLATE);
        field(inspection, "sampleQuantity", "sample_quantity", "integer", "样品数量", true, false, ContentPolicy.KEEP_ORIGINAL);
        field(inspection, "failedQuantity", "failed_quantity", "integer", "失效数量", true, false, ContentPolicy.KEEP_ORIGINAL);
        relation(inspection, "inspections", "检测明细", main);

        var investigation = entity("quality_investigation", "团队调查记录", false);
        field(investigation, "exceptionId", "exception_id", "string", "异常单", true, false, ContentPolicy.KEEP_ORIGINAL);
        field(investigation, "description", "description", "text", "调查意见", true, false, ContentPolicy.AUTO_TRANSLATE);
        relation(investigation, "investigations", "团队调查记录", main);
    }

    private Metadata entity(String alias, String title, boolean dataScope) {
        var existing = metadata.list(Criteria.of().eq("applicationAlias", "quality").eq("alias", alias), ONE)
                .stream().findFirst().orElse(null);
        if (existing != null) return existing;
        var value = new Metadata();
        value.setApplicationAlias("quality"); value.setAlias(alias); value.setTableName(alias);
        value.setTitle(title); value.setDataScopeEnabled(dataScope);
        return metadata.select(metadata.insert(value));
    }

    private MetadataField field(Metadata entity, String name, String column, String spec, String title,
                                boolean required, boolean titleField, ContentPolicy policy) {
        var existing = fields.list(Criteria.of().eq("metadataId", entity.getId()).eq("fieldName", name), ONE)
                .stream().findFirst().orElse(null);
        if (existing != null) return existing;
        var value = new MetadataField();
        value.setMetadataId(entity.getId()); value.setFieldName(name); value.setColumnName(column);
        value.setFieldSpecAlias(spec); value.setTitle(title); value.setRequired(required);
        value.setTitleField(titleField); value.setContentPolicy(policy);
        return fields.select(fields.insert(value));
    }

    private ModuleMetadataRelation relation(Metadata entity, String alias, String title, Metadata parent) {
        var existing = relations.list(Criteria.of().eq("moduleAlias", MODULE_ALIAS).eq("relationAlias", alias), ONE)
                .stream().findFirst().orElse(null);
        if (existing != null) return existing;
        var value = new ModuleMetadataRelation();
        value.setModuleAlias(MODULE_ALIAS); value.setMetadataId(entity.getId()); value.setRelationAlias(alias);
        value.setTitle(title); value.setRelationRole(parent == null ? RelationRole.MAIN : RelationRole.CHILD);
        if (parent != null) {
            value.setParentMetadataId(parent.getId()); value.setForeignKey("exceptionId"); value.setAutoPopulate(true);
        }
        return relations.select(relations.insert(value));
    }

    private void reference(MetadataField product, ModuleMetadataRelation owner) {
        if (references.findForRelation(product.getId(), owner.getId()) != null) return;
        var reference = new MetadataFieldReferenceConfig();
        reference.setMetadataFieldId(product.getId()); reference.setRelationId(owner.getId());
        reference.setTargetModuleAlias(ProductService.MODULE_ALIAS);
        reference.setProjectionMappings("title:productTitle,code:productCode");
        references.insert(reference);
    }

    private void mount() {
        for (String alias : List.of(EquipmentCategoryService.MODULE_ALIAS, ProductService.MODULE_ALIAS, MODULE_ALIAS)) {
            if (modules.select(alias) == null) throw new IllegalStateException("Missing quality sample module: " + alias);
            modules.enable(alias);
        }
        String id = "platform.menu.module." + MODULE_ALIAS;
        if (menus.select(id) == null) {
            var menu = new Menu();
            menu.setId(id); menu.setSchemeId(MenuSchemeService.ADMIN_SCHEME_ID);
            menu.setParentId(QualityDemoMenuDeclarations.ROOT); menu.setTitle("质量异常单");
            menu.setModuleAlias(MODULE_ALIAS); menu.setOpenMode(MenuOpenMode.TAB); menu.setSystemManaged(true);
            menu.setSortOrder(130); menus.insert(menu);
        }
        menus.enable(QualityDemoMenuDeclarations.ROOT);
        for (String alias : List.of(EquipmentCategoryService.MODULE_ALIAS, ProductService.MODULE_ALIAS, MODULE_ALIAS))
            menus.enable("platform.menu.module." + alias);
    }
}
