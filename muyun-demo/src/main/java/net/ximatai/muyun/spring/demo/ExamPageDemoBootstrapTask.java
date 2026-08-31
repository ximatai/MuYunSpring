package net.ximatai.muyun.spring.demo;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelation;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelationService;
import net.ximatai.muyun.spring.platform.metadata.RelationRole;
import net.ximatai.muyun.spring.platform.runtime.PlatformBootstrapTask;
import net.ximatai.muyun.spring.platform.ui.PlatformPageContractType;
import net.ximatai.muyun.spring.platform.ui.PlatformPageDefinition;
import net.ximatai.muyun.spring.platform.ui.PlatformPageDefinitionService;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationClientType;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationRevision;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationRevisionPublishService;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationRevisionService;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationRevisionStatus;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationScopeType;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationTemplateCatalog;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationVariant;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationVariantService;

import java.util.Comparator;

/**
 * Creates the published management page consumed by the dynamic examination runtime.
 *
 * <p>This is deliberately an application fixture rather than a generic page seeding mechanism:
 * it owns one education business baseline and creates it through the same page, variant, revision
 * and publication services used by governance.  A same-tree draft follows the published baseline
 * so the composition workspace always has an editable starting point.</p>
 */
public class ExamPageDemoBootstrapTask implements PlatformBootstrapTask {
    public static final String PAGE_ALIAS = "management";
    public static final String PAGE_TITLE = "考试管理页";
    private static final PageRequest ONE = PageRequest.of(1, 1);

    private final ModuleMetadataRelationService relationService;
    private final PlatformPageDefinitionService pageService;
    private final PlatformPresentationVariantService variantService;
    private final PlatformPresentationRevisionService revisionService;
    private final PlatformPresentationRevisionPublishService publishService;

    public ExamPageDemoBootstrapTask(ModuleMetadataRelationService relationService,
                                     PlatformPageDefinitionService pageService,
                                     PlatformPresentationVariantService variantService,
                                     PlatformPresentationRevisionService revisionService,
                                     PlatformPresentationRevisionPublishService publishService) {
        this.relationService = relationService;
        this.pageService = pageService;
        this.variantService = variantService;
        this.revisionService = revisionService;
        this.publishService = publishService;
    }

    @Override
    public String name() {
        return "demo-academic-evaluation-page";
    }

    @Override
    public int order() {
        return 220;
    }

    @Override
    public void run() {
        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(
                CurrentUser.systemUser("demo-exam-page-bootstrap", "Exam Page Demo Bootstrap"));
             TenantContext.Scope ignoredTenant = TenantContext.system("configure exam demo page")) {
            ModuleMetadataRelation main = requireMainRelation();
            PlatformPageDefinition page = ensurePage(main);
            PlatformPresentationVariant variant = ensureGlobalWebVariant(page);
            PlatformPresentationRevision published = ensurePublishedBaseline(variant);
            ensureFollowUpDraft(variant, published);
        }
    }

    private ModuleMetadataRelation requireMainRelation() {
        return relationService.list(Criteria.of().eq("moduleAlias", ExamDemoBootstrapTask.MODULE_ALIAS)
                        .eq("relationRole", RelationRole.MAIN), ONE)
                .stream().findFirst().orElseThrow(() -> new IllegalStateException(
                        "exam demo page requires the published MAIN metadata relation"));
    }

    private PlatformPageDefinition ensurePage(ModuleMetadataRelation main) {
        return pageService.resolveGlobalPage(ExamDemoBootstrapTask.MODULE_ALIAS, PAGE_ALIAS)
                .orElseGet(() -> {
                    PlatformPageDefinition page = new PlatformPageDefinition();
                    page.setModuleAlias(ExamDemoBootstrapTask.MODULE_ALIAS);
                    page.setAlias(PAGE_ALIAS);
                    page.setTitle(PAGE_TITLE);
                    page.setContractType(PlatformPageContractType.MANAGEMENT);
                    page.setMainRelationId(main.getId());
                    return pageService.select(pageService.insert(page));
                });
    }

    private PlatformPresentationVariant ensureGlobalWebVariant(PlatformPageDefinition page) {
        PlatformPresentationVariant variant = variantService.list(Criteria.of()
                        .eq("pageId", page.getId())
                        .eq("clientType", PlatformPresentationClientType.WEB)
                        .eq("scopeType", PlatformPresentationScopeType.GLOBAL)
                        .isNull("tenantId")
                        .isNull("organizationId"), ONE)
                .stream().findFirst().orElse(null);
        if (variant != null) return variant;
        PlatformPresentationVariant created = new PlatformPresentationVariant();
        created.setPageId(page.getId());
        created.setClientType(PlatformPresentationClientType.WEB);
        created.setScopeType(PlatformPresentationScopeType.GLOBAL);
        created.setTitle("考试管理 Web 全局呈现");
        return variantService.select(variantService.insert(created));
    }

    private PlatformPresentationRevision ensurePublishedBaseline(PlatformPresentationVariant variant) {
        PlatformPresentationRevision published = revisions(variant.getId()).stream()
                .filter(revision -> revision.getStatus() == PlatformPresentationRevisionStatus.PUBLISHED)
                .findFirst().orElse(null);
        if (published != null) return published;
        PlatformPresentationRevision baseline = newRevision(variant.getId(), nextRevisionNo(variant.getId()),
                PlatformPresentationRevisionStatus.DRAFT, baselineTree());
        String revisionId = revisionService.insert(baseline);
        return publishService.publish(revisionId);
    }

    private void ensureFollowUpDraft(PlatformPresentationVariant variant, PlatformPresentationRevision published) {
        boolean hasDraft = revisions(variant.getId()).stream()
                .anyMatch(revision -> revision.getStatus() == PlatformPresentationRevisionStatus.DRAFT);
        if (hasDraft) return;
        PlatformPresentationRevision draft = newRevision(variant.getId(), nextRevisionNo(variant.getId()),
                PlatformPresentationRevisionStatus.DRAFT, published.getUiTreeJson());
        draft.setTitle("考试管理页草稿");
        revisionService.insert(draft);
    }

    private java.util.List<PlatformPresentationRevision> revisions(String variantId) {
        return revisionService.list(Criteria.of().eq("variantId", variantId));
    }

    private int nextRevisionNo(String variantId) {
        return revisions(variantId).stream().map(PlatformPresentationRevision::getRevisionNo)
                .filter(java.util.Objects::nonNull).max(Comparator.naturalOrder()).orElse(0) + 1;
    }

    private PlatformPresentationRevision newRevision(String variantId, int revisionNo,
                                                      PlatformPresentationRevisionStatus status, String tree) {
        PlatformPresentationRevision revision = new PlatformPresentationRevision();
        revision.setVariantId(variantId);
        revision.setRevisionNo(revisionNo);
        revision.setTemplateAlias(PlatformPresentationTemplateCatalog.MANAGEMENT_ALIAS);
        revision.setTemplateVersion(PlatformPresentationTemplateCatalog.MANAGEMENT_VERSION);
        revision.setStatus(status);
        revision.setUiTreeJson(tree);
        revision.setTitle(PAGE_TITLE + " v" + revisionNo);
        return revision;
    }

    static String baselineTree() {
        return """
                {"template":"management","templateVersion":1,
                 "props":{"list":{"searchPlaceholder":"搜索考试名称"}},
                 "nodes":[
                   {"slot":"list","title":"考试列表","fields":["title","classroomId","subjectCategoryId","examDate"]},
                   {"slot":"form","title":"考试详情 / 编辑","fields":["title","classroomId","subjectCategoryId","examDate"],
                    "relations":[{"relation":"participants","title":"参考学生",
                    "fields":["studentId","studentNo","studentTitle","score","attendanceStatus"]}]}
                 ]}
                """;
    }
}
