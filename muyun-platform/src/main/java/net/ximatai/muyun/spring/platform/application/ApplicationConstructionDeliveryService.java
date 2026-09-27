package net.ximatai.muyun.spring.platform.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.ability.TreeAbility;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.model.EntityLifecycle;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.menu.*;
import net.ximatai.muyun.spring.platform.metadata.MetadataField;
import net.ximatai.muyun.spring.platform.runtime.DynamicRuntimeActivationService;
import net.ximatai.muyun.spring.platform.ui.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Requirements-bound publication; page and menu nodes retain separate confirmation/transaction boundaries. */
@Service
public class ApplicationConstructionDeliveryService {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private final net.ximatai.muyun.database.core.IDatabaseOperations<?> database;
    private final ApplicationConstructionPlanService plans;
    private final ApplicationConstructionFieldService fields;
    private final ApplicationConstructionDeliveryDao receipts;
    private final ApplicationConstructionAcceptanceDao acceptances;
    private final PlatformPageDefinitionService pages;
    private final PlatformPresentationVariantService variants;
    private final PlatformPresentationRevisionService revisions;
    private final PlatformPresentationRevisionPublishService publisher;
    private final PlatformPresentationTemplateCatalog templates;
    private final ApplicationConstructionPageCompiler compiler;
    private final MenuService menus;
    private final MenuSchemeService schemes;
    private final DynamicRuntimeActivationService activation;
    private final ActionExecutionPolicyService permissions;

    public ApplicationConstructionDeliveryService(net.ximatai.muyun.database.core.IDatabaseOperations<?> database, ApplicationConstructionPlanService plans, ApplicationConstructionFieldService fields,
            ApplicationConstructionDeliveryDao receipts, ApplicationConstructionAcceptanceDao acceptances, PlatformPageDefinitionService pages, PlatformPresentationVariantService variants,
            PlatformPresentationRevisionService revisions, PlatformPresentationRevisionPublishService publisher,
            PlatformPresentationTemplateCatalog templates, ApplicationConstructionPageCompiler compiler, MenuService menus,
            MenuSchemeService schemes, DynamicRuntimeActivationService activation, ActionExecutionPolicyService permissions) {
        this.database = Objects.requireNonNull(database); this.plans = Objects.requireNonNull(plans); this.fields = Objects.requireNonNull(fields); this.receipts = Objects.requireNonNull(receipts); this.acceptances = Objects.requireNonNull(acceptances); this.pages = Objects.requireNonNull(pages); this.variants = Objects.requireNonNull(variants);
        this.revisions = Objects.requireNonNull(revisions); this.publisher = Objects.requireNonNull(publisher); this.templates = Objects.requireNonNull(templates); this.compiler = Objects.requireNonNull(compiler);
        this.menus = Objects.requireNonNull(menus); this.schemes = Objects.requireNonNull(schemes); this.activation = Objects.requireNonNull(activation); this.permissions = Objects.requireNonNull(permissions);
    }
    public enum Kind { PAGE, ENTRY }
    public record Proposal(int planRevision, String objectKey, Kind kind, String title,
                           List<String> listFields, List<String> formFields, List<String> searchFields) {
        public Proposal {
            if (planRevision < 1 || kind == null || objectKey == null || !objectKey.matches("[a-z][a-z0-9_-]{0,63}"))
                throw new IllegalArgumentException("建设节点参数无效");
            if (title == null || title.isBlank() || title.length() > 120) throw new IllegalArgumentException("标题不能为空或过长");
            listFields = checkedNames(listFields); formFields = checkedNames(formFields); searchFields = checkedNames(searchFields);
            if (kind == Kind.PAGE && (listFields.isEmpty() || formFields.isEmpty())) throw new IllegalArgumentException("列表和表单至少需要一个字段");
            if (kind == Kind.ENTRY && (!listFields.isEmpty() || !formFields.isEmpty() || !searchFields.isEmpty()))
                throw new IllegalArgumentException("入口确认不包含页面配置");
        }
    }
    public record Preview(Proposal proposal, String moduleAlias, List<String> lines, String fingerprint) {}
    public record Command(String requestId, Proposal proposal, String fingerprint) {}
    public record Receipt(String requestId, String objectKey, int planRevision, Kind kind, String moduleAlias,
                          String pageId, String variantId, String revisionId, String menuId, int metadataVersion) {}
    public record Progress(String objectKey, String moduleAlias, String runtimeStatus, boolean pagePublished,
                           boolean entryVisible, String menuId, boolean needsReview, boolean acceptanceConfirmed, List<String> remainingWork, List<Receipt> receipts, List<ApplicationConstructionRequirements.Evidence> requirements) {}

    public Preview preview(String planId, Proposal proposal) {
        requireOperator(); requirePermissions(proposal.kind());
        var plan = plans.read(planId);
        if (plan.revision() != proposal.planRevision()) throw new IllegalArgumentException("需求版本已变化，请重新预检");
        ApplicationConstructionRequirements.requireBuildable(plan.content(), proposal.objectKey());
        var description = fields.describe(planId, proposal.objectKey());
        if (ApplicationConstructionRequirements.missingConfiguration(ApplicationConstructionRequirements.evaluate(plan.content(), proposal.objectKey(), description.fields())))
            throw new IllegalArgumentException("已确认要求尚未落实到实际字段约束，请先补齐配置再发布页面或入口");
        var binding = plan.initializations().stream().filter(value -> value.objectKey().equals(proposal.objectKey())).findFirst().orElseThrow();
        try (var ignored = TenantContext.system("construction delivery preview")) {
            var own = latest(planId, proposal.objectKey(), Kind.PAGE);
            var page = own == null ? null : pages.select(own.pageId());
            if (own != null && (page == null || !page.getModuleAlias().equals(binding.moduleAlias()) || !page.getMainRelationId().equals(binding.relationId())))
                throw new IllegalArgumentException("已建页面绑定发生变化，请核对后继续");
            if (own != null && !published(own)) throw new IllegalArgumentException("已建页面已被其他修订替换或停用，请先核对正式页面");
            if (page == null && pages.resolveGlobalPage(binding.moduleAlias(), "management").isPresent())
                throw new IllegalArgumentException("模块已有独立页面，不能自动接管");
            var lines = new ArrayList<String>();
            Object baseline;
            if (proposal.kind() == Kind.PAGE) {
                var byName = new LinkedHashMap<String, MetadataField>();
                description.fields().forEach(field -> byName.put(field.getFieldName(), field));
                for (String name : java.util.stream.Stream.of(proposal.listFields(), proposal.formFields(), proposal.searchFields()).flatMap(List::stream).toList())
                    if (!byName.containsKey(name)) throw new IllegalArgumentException("字段不在实际目录中：" + name);
                for (var field : description.fields()) {
                    if (Boolean.TRUE.equals(field.getRequired()) && !SYSTEM_FIELDS.contains(field.getFieldName()) && !proposal.formFields().contains(field.getFieldName()))
                        throw new IllegalArgumentException("表单必须包含必填字段：" + field.getTitle());
                }
                var requiredInputs = requiredInputs(plan.content(), proposal.objectKey());
                if (!proposal.formFields().containsAll(requiredInputs))
                    throw new IllegalArgumentException("页面表单必须包含已确认需求对应的登记字段，包含选填字段");
                if (proposal.formFields().stream().anyMatch(SYSTEM_FIELDS::contains)) throw new IllegalArgumentException("系统字段不能作为可编辑表单字段");
                var candidate = page == null ? page(binding, proposal.title()) : page;
                var revision = revision(null, proposal, 1);
                templates.validateUiTree(revision, templates.require("management", PlatformPresentationTemplateCatalog.MODE_AWARE_VERSION, PlatformPresentationClientType.WEB, PlatformPageContractType.MANAGEMENT));
                compiler.validate(candidate, revision);
                lines.add("列表：" + titles(proposal.listFields(), byName));
                lines.add("表单与详情：" + titles(proposal.formFields(), byName));
                lines.add("快速查询：" + titles(proposal.searchFields(), byName));
                lines.add("仅发布列表、表单、详情及查询配置；不修改字段约束、关系或其他业务规则。");
                baseline = page == null ? "NEW" : List.of(page, variants.requireVisibleVariant(own.variantId()), revisions.list(Criteria.of().eq("variantId", own.variantId())));
            } else {
                if (own == null || !published(own)) throw new IllegalArgumentException("请先完成页面发布");
                if (latest(planId, proposal.objectKey(), Kind.ENTRY) != null || menus.currentUserVisibleModuleMenu(binding.moduleAlias()) != null)
                    throw new IllegalArgumentException("已有访问入口，请查询进度，不要重复创建");
                var scheme = schemes.resolveCurrentUserScheme(CurrentUserContext.currentUser().orElseThrow());
                if (scheme.getScopeType() != MenuScopeType.SYSTEM) throw new IllegalArgumentException("当前仅支持系统配置工作台入口");
                lines.add("在当前系统工作台菜单方案“" + scheme.getTitle() + "”下创建入口：" + proposal.title());
                lines.add("入口使用已发布页面，不授予角色或租户业务用户新的业务权限。");
                baseline = List.of(own, scheme, revisions.select(own.revisionId()));
            }
            lines.add("依据需求第 " + plan.revision() + " 版；业务可用性仍需按验收例子核对。");
            return new Preview(proposal, binding.moduleAlias(), List.copyOf(lines), digest(json(List.of(planId, proposal, description, baseline))));
        }
    }

    @Transactional
    public Receipt confirm(String planId, Command command) {
        requireOperator();
        if (command == null || command.proposal() == null || command.fingerprint() == null) throw new IllegalArgumentException("确认参数无效");
        requirePermissions(command.proposal().kind()); plans.read(planId); requireRequestId(command.requestId());
        PlatformAbilityRuntime.lockMutationPartition("platform.application-construction-plan", planId);
        String id = digest(planId + ":" + command.requestId()).substring(0, 32);
        var previous = receipts.findById(id);
        if (previous != null) {
            if (!previous.getRequestDigest().equals(digest(json(command)))) throw new IllegalArgumentException("同一次确认内容已变化");
            return receipt(previous);
        }
        lockBaseline(planId, command.proposal().objectKey());
        var checked = preview(planId, command.proposal());
        if (!checked.fingerprint().equals(command.fingerprint())) throw new IllegalArgumentException("建设预检已过期，请重新审阅");
        var proposal = command.proposal();
        var plan = plans.read(planId);
        var binding = plan.initializations().stream().filter(value -> value.objectKey().equals(proposal.objectKey())).findFirst().orElseThrow();
        try (var ignored = TenantContext.system("confirmed construction delivery")) {
            var old = latest(planId, proposal.objectKey(), Kind.PAGE);
            Receipt result;
            if (proposal.kind() == Kind.PAGE) {
                String pageId, variantId;
                int number = 1;
                if (old == null) {
                    pageId = pages.insert(page(binding, proposal.title()));
                    var variant = new PlatformPresentationVariant(); variant.setPageId(pageId); variant.setTitle(proposal.title());
                    variant.setEnabled(true); variant.setClientType(PlatformPresentationClientType.WEB); variant.setScopeType(PlatformPresentationScopeType.GLOBAL);
                    variantId = variants.insert(variant);
                } else {
                    pageId = old.pageId(); variantId = old.variantId();
                    number = revisions.list(Criteria.of().eq("variantId", variantId)).stream().mapToInt(PlatformPresentationRevision::getRevisionNo).max().orElse(0) + 1;
                }
                var revision = revision(variantId, proposal, number);
                String revisionId = revisions.insert(revision);
                publisher.publish(revisionId);
                result = new Receipt(command.requestId(), proposal.objectKey(), plan.revision(), Kind.PAGE, binding.moduleAlias(), pageId, variantId, revisionId, null,
                        fields.describe(planId, proposal.objectKey()).metadataVersion());
            } else {
                var scheme = schemes.resolveCurrentUserScheme(CurrentUserContext.currentUser().orElseThrow());
                var menu = new Menu(); menu.setSchemeId(scheme.getId()); menu.setParentId(TreeAbility.ROOT_ID);
                menu.setTitle(proposal.title()); menu.setModuleAlias(binding.moduleAlias()); menu.setEnabled(true);
                menu.setOpenMode(MenuOpenMode.TAB); menu.setPageMode(MenuPageMode.LIST);
                String menuId = menus.insert(menu);
                result = new Receipt(command.requestId(), proposal.objectKey(), plan.revision(), Kind.ENTRY, binding.moduleAlias(), old.pageId(), old.variantId(), old.revisionId(), menuId, old.metadataVersion());
            }
            var stored = new ApplicationConstructionDelivery(); stored.setId(id); stored.setPlanId(planId); stored.setPlanRevision(plan.revision());
            stored.setObjectKey(proposal.objectKey()); stored.setRequestId(command.requestId()); stored.setRequestDigest(digest(json(command)));
            stored.setModuleAlias(binding.moduleAlias()); stored.setKind(result.kind().name()); stored.setReceiptJson(json(result));
            EntityLifecycle.prepareInsert(stored, Instant.now()); receipts.insert(stored);
            return result;
        }
    }
    public Receipt result(String planId, String requestId) {
        requireOperator(); plans.read(planId); requireRequestId(requestId);
        var stored = receipts.findById(digest(planId + ":" + requestId).substring(0, 32));
        return stored == null ? null : receipt(stored);
    }
    public Progress progress(String planId, String objectKey) {
        requireOperator(); var plan = plans.read(planId);
        var description = fields.describe(planId, objectKey);
        try (var ignored = TenantContext.system("construction progress")) {
            var page = latest(planId, objectKey, Kind.PAGE);
            var entry = latest(planId, objectKey, Kind.ENTRY);
            boolean published = page != null && published(page);
            var menu = entry == null ? null : menus.currentUserVisibleMenu(entry.menuId());
            boolean visible = menu != null && menu.getModuleAlias().equals(description.moduleAlias());
            boolean stale = page != null && (page.planRevision() != plan.revision() || page.metadataVersion() != description.metadataVersion()
                    || !pageCoversRequirements(plan.content(), objectKey, page));
            String runtime = activation.status(description.moduleAlias()).status();
            var remaining = new ArrayList<String>();
            if (!published) remaining.add("页面尚未发布或已被其他修订替换");
            if (!visible) remaining.add("工作台入口尚不可见");
            if (stale) remaining.add("需求或字段基线已变化，请重新核对页面与验收范围");
            if (!"ACTIVE".equals(runtime)) remaining.add("模块运行态尚未激活");
            String baseline = acceptanceBaseline(plan, description, page, menu);
            var evidence = ApplicationConstructionRequirements.evaluate(plan.content(), objectKey, description.fields());
            boolean requirementsReady = !ApplicationConstructionRequirements.blocked(evidence) && !ApplicationConstructionRequirements.missingConfiguration(evidence);
            if (!requirementsReady) remaining.add("本期要求尚有未兑现项，请逐项核对，不能以页面发布代替完成");
            boolean accepted = requirementsReady && published && visible && !stale && "ACTIVE".equals(runtime) &&
                    !acceptances.list(Criteria.of().eq("planId", planId).eq("objectKey", objectKey).eq("baseline", baseline)).isEmpty();
            if (!accepted) remaining.add("按已确认验收例子实际录入、查询和查看详情；发布回执不等于业务验收");
            return new Progress(objectKey, description.moduleAlias(), runtime, published, visible, visible ? menu.getId() : null, stale, accepted,
                    List.copyOf(remaining), receipts.list(Criteria.of().eq("planId", planId).eq("objectKey", objectKey)).stream().map(ApplicationConstructionDeliveryService::receipt).toList(), evidence);
        }
    }
    /** Planning choices are not execution grants; every proposal is still preflighted and confirmed. */
    public enum TaskAction { REVIEW_REQUIREMENTS, INITIALIZE, VERIFY_RUNTIME, CONFIGURE_FIELDS, REVIEW_CONFIGURATION, PUBLISH_PAGE, CREATE_ENTRY, VERIFY_BUSINESS }
    public record TaskOption(TaskAction action, String explanation) {}
    public record TaskObject(String objectKey, String title, boolean complete, List<TaskOption> options,
                             List<ApplicationConstructionRequirements.Evidence> requirements) {}
    public record Task(int planRevision, List<TaskObject> objects) {}

    /** Recompute choices from current facts. No persisted cursor or prescribed order across objects. */
    public Task task(String planId) {
        requireOperator();
        var plan = plans.read(planId);
        var objects = new ArrayList<TaskObject>();
        for (var object : plan.content().objects()) {
            boolean initialized = plan.initializations().stream().anyMatch(item -> item.objectKey().equals(object.key()));
            var evidence = ApplicationConstructionRequirements.evaluate(plan.content(), object.key(), List.of());
            var options = new ArrayList<TaskOption>();
            boolean complete = false;
            if (ApplicationConstructionRequirements.blocked(evidence) || !plan.content().questions().isEmpty()) {
                options.add(new TaskOption(TaskAction.REVIEW_REQUIREMENTS, "先商定本期兑现方式与未决问题"));
            } else if (!initialized) {
                options.add(new TaskOption(TaskAction.INITIALIZE, "准备建立此业务对象，尚不能录入业务"));
            } else {
                var progress = progress(planId, object.key());
                evidence = progress.requirements();
                complete = progress.acceptanceConfirmed();
                if (!"ACTIVE".equals(progress.runtimeStatus())) {
                    options.add(new TaskOption(TaskAction.VERIFY_RUNTIME, "先核实已提交配置的可用状态，不重复创建"));
                } else if (!complete) {
                    boolean missing = ApplicationConstructionRequirements.missingConfiguration(evidence);
                    options.add(new TaskOption(TaskAction.CONFIGURE_FIELDS, missing
                            ? "对照尚缺的配置证据补齐登记内容" : "如本期内容仍需补充，可准备字段变更；无需为推进进度额外增加字段"));
                    if (progress.needsReview())
                        options.add(new TaskOption(TaskAction.REVIEW_CONFIGURATION, "核对需求与配置变化，保留已生效成果"));
                    if (!missing) {
                        options.add(new TaskOption(TaskAction.PUBLISH_PAGE, progress.pagePublished()
                                ? "如需调整页面，可准备新修订；已有页面无需重复发布" : "依据实际字段准备录入和查询页面"));
                        if (progress.pagePublished() && !progress.needsReview()) {
                            if (!progress.entryVisible())
                                options.add(new TaskOption(TaskAction.CREATE_ENTRY, "核实已有入口后准备访问入口，不自动授权"));
                            else options.add(new TaskOption(TaskAction.VERIFY_BUSINESS, "实际试用并核对人工项，再由用户确认验收"));
                        }
                    }
                }
            }
            objects.add(new TaskObject(object.key(), object.name(), complete, List.copyOf(options), evidence));
        }
        return new Task(plan.revision(), List.copyOf(objects));
    }

    public record AcceptancePreview(String objectKey, int planRevision, List<String> checks, String fingerprint) {}
    public record AcceptanceCommand(String requestId, String objectKey, String fingerprint) {}
    public record AcceptanceReceipt(String requestId, String objectKey, int planRevision, String baseline) {}

    public AcceptancePreview previewAcceptance(String planId, String objectKey) {
        var progress = progress(planId, objectKey);
        if (!progress.pagePublished() || !progress.entryVisible() || progress.needsReview() || !"ACTIVE".equals(progress.runtimeStatus()))
            throw new IllegalArgumentException("页面、入口或建设基线尚未就绪，不能确认验收");
        if (ApplicationConstructionRequirements.blocked(progress.requirements()) || ApplicationConstructionRequirements.missingConfiguration(progress.requirements()))
            throw new IllegalArgumentException("本期要求尚未全部具备配置证据或明确人工核验方式，不能确认验收");
        var plan = plans.read(planId);
        if (!plan.content().questions().isEmpty()) throw new IllegalArgumentException("方案仍有未决问题，请先澄清并重新确认范围");
        try (var ignored = TenantContext.system("construction acceptance preview")) {
            var description = fields.describe(planId, objectKey);
            var page = latest(planId, objectKey, Kind.PAGE);
            var menu = menus.currentUserVisibleMenu(progress.menuId());
            var checks = new ArrayList<String>();
            checks.add("请实际验证以下需求，而非仅根据配置发布成功确认；未支持的要求应先修订方案。");
            for (var evidence : progress.requirements())
                checks.add((evidence.status() == ApplicationConstructionRequirements.Status.MANUAL_CHECK_REQUIRED ? "需实际人工核验：" : "配置证据匹配，仍须业务试用：")
                        + evidence.statement() + " — " + evidence.explanation());
            checks.addAll(plan.content().rules().stream().map(rule -> "业务规则：" + rule).toList());
            checks.addAll(plan.content().acceptanceExamples().stream().map(example -> "验收例子：" + example).toList());
            checks.add("已在选定业务租户内实际验证录入、查询和详情；系统配置身份不替代业务租户范围。");
            checks.add("已核对需求与实际字段必填、唯一、数值范围及查询行为一致。");
            return new AcceptancePreview(objectKey, plan.revision(), List.copyOf(checks), acceptanceBaseline(plan, description, page, menu));
        }
    }
    @Transactional
    public AcceptanceReceipt confirmAcceptance(String planId, AcceptanceCommand command) {
        requireOperator(); plans.read(planId); requireRequestId(command.requestId());
        PlatformAbilityRuntime.lockMutationPartition("platform.application-construction-plan", planId);
        String id = digest(planId + ":" + command.requestId()).substring(0, 32);
        String requestDigest = digest(json(command));
        var prior = acceptances.findById(id);
        if (prior != null) {
            if (!prior.getRequestDigest().equals(requestDigest)) throw new IllegalArgumentException("验收确认内容已变化");
            return acceptanceReceipt(prior);
        }
        lockBaseline(planId, command.objectKey());
        var preview = previewAcceptance(planId, command.objectKey());
        if (!preview.fingerprint().equals(command.fingerprint())) throw new IllegalArgumentException("验收基线已变化，请重新核对");
        var accepted = new ApplicationConstructionAcceptance(); accepted.setId(id); accepted.setPlanId(planId);
        accepted.setPlanRevision(preview.planRevision()); accepted.setObjectKey(command.objectKey()); accepted.setRequestId(command.requestId());
        accepted.setRequestDigest(requestDigest); accepted.setBaseline(preview.fingerprint());
        EntityLifecycle.prepareInsert(accepted, Instant.now()); acceptances.insert(accepted);
        return acceptanceReceipt(accepted);
    }
    public AcceptanceReceipt acceptance(String planId, String requestId) {
        requireOperator(); plans.read(planId); requireRequestId(requestId);
        var receipt = acceptances.findById(digest(planId + ":" + requestId).substring(0, 32));
        return receipt == null ? null : acceptanceReceipt(receipt);
    }
    private static AcceptanceReceipt acceptanceReceipt(ApplicationConstructionAcceptance value) {
        return new AcceptanceReceipt(value.getRequestId(), value.getObjectKey(), value.getPlanRevision(), value.getBaseline());
    }
    private static List<String> requiredInputs(ApplicationConstructionPlanContent content, String objectKey) {
        return content.requirements().stream().filter(item -> item.objectKey().equals(objectKey))
                .filter(item -> item.mode() == ApplicationConstructionRequirement.Mode.FIELD
                        || item.mode() == ApplicationConstructionRequirement.Mode.REQUIRED
                        || item.mode() == ApplicationConstructionRequirement.Mode.UNIQUE)
                .map(ApplicationConstructionRequirement::fieldName).distinct().toList();
    }
    private boolean pageCoversRequirements(ApplicationConstructionPlanContent content, String objectKey, Receipt receipt) {
        var revision = revisions.select(receipt.revisionId());
        if (revision == null) return false;
        try {
            var formFields = new HashSet<String>();
            for (var node : JSON.readTree(revision.getUiTreeJson()).path("nodes"))
                if ("form".equals(node.path("slot").asText()))
                    node.path("fields").forEach(field -> formFields.add(field.asText()));
            return formFields.containsAll(requiredInputs(content, objectKey));
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            return false;
        }
    }
    private String acceptanceBaseline(ApplicationConstructionPlanService.Snapshot plan, ApplicationConstructionFieldService.Description description, Receipt page, Menu menu) {
        // Bind acceptance to actual configuration, not only to a model-authored completion statement.
        return digest(json(Arrays.asList(plan.planId(), plan.revision(), description, page,
                page == null ? null : pages.select(page.pageId()),
                page == null ? null : variants.select(page.variantId()),
                page == null ? null : revisions.select(page.revisionId()), menu)));
    }
    private void lockBaseline(String planId, String objectKey) {
        var binding = plans.read(planId).initializations().stream().filter(value -> value.objectKey().equals(objectKey)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("业务对象尚未初始化"));
        database.query("select id from platform_metadata where id = ? for update", binding.metadataId());
        var page = latest(planId, objectKey, Kind.PAGE);
        if (page != null) {
            database.query("select id from platform_page_definition where id = ? for update", page.pageId());
            database.query("select id from platform_presentation_variant where id = ? for update", page.variantId());
        }
        var entry = latest(planId, objectKey, Kind.ENTRY);
        if (entry != null) database.query("select id from platform_menu where id = ? for update", entry.menuId());
    }
    private boolean published(Receipt receipt) {
        var page = pages.select(receipt.pageId()); var variant = variants.select(receipt.variantId()); var revision = revisions.select(receipt.revisionId());
        return page != null && Boolean.TRUE.equals(page.getEnabled()) && variant != null && Boolean.TRUE.equals(variant.getEnabled())
                && receipt.moduleAlias().equals(page.getModuleAlias()) && receipt.pageId().equals(variant.getPageId())
                && variant.getClientType() == PlatformPresentationClientType.WEB && variant.getScopeType() == PlatformPresentationScopeType.GLOBAL
                && revision != null && receipt.variantId().equals(revision.getVariantId())
                && Boolean.TRUE.equals(revision.getEnabled()) && revision.getStatus() == PlatformPresentationRevisionStatus.PUBLISHED;
    }
    private Receipt latest(String planId, String objectKey, Kind kind) {
        return receipts.list(Criteria.of().eq("planId", planId).eq("objectKey", objectKey).eq("kind", kind.name())).stream()
                .max(Comparator.comparing(ApplicationConstructionDelivery::getCreatedAt).thenComparing(ApplicationConstructionDelivery::getId))
                .map(ApplicationConstructionDeliveryService::receipt).orElse(null);
    }
    public static Receipt receipt(ApplicationConstructionDelivery stored) {
        try { return JSON.readValue(stored.getReceiptJson(), Receipt.class); }
        catch (Exception error) { throw new IllegalStateException("建设回执无法读取", error); }
    }
    private static PlatformPageDefinition page(ApplicationConstructionPlanService.Initialization binding, String title) {
        var page = new PlatformPageDefinition(); page.setModuleAlias(binding.moduleAlias()); page.setAlias("management");
        page.setMainRelationId(binding.relationId()); page.setContractType(PlatformPageContractType.MANAGEMENT); page.setTitle(title); page.setEnabled(true);
        return page;
    }
    /** This node owns field placement, while the mode-aware template inherits standard module actions. */
    private static PlatformPresentationRevision revision(String variantId, Proposal proposal, int number) {
        var revision = new PlatformPresentationRevision(); revision.setVariantId(variantId); revision.setRevisionNo(number);
        revision.setTemplateAlias("management"); revision.setTemplateVersion(PlatformPresentationTemplateCatalog.MODE_AWARE_VERSION); revision.setEnabled(true); revision.setTitle(proposal.title());
        revision.setStatus(PlatformPresentationRevisionStatus.DRAFT);
        revision.setUiTreeJson(json(Map.of("template", "management", "templateVersion", PlatformPresentationTemplateCatalog.MODE_AWARE_VERSION, "mode", "LIST_CARD", "quickSearchFields", proposal.searchFields(),
                "nodes", List.of(Map.of("slot", "list", "title", proposal.title(), "fields", proposal.listFields()),
                        Map.of("slot", "form", "title", proposal.title(), "fields", proposal.formFields())))));
        return revision;
    }
    private void requireOperator() {
        if (!CurrentUserContext.currentUser().map(user -> user.system()).orElse(false)) throw new PlatformAccessDeniedException("页面与入口建设要求系统配置身份");
    }
    private void requirePermissions(Kind kind) {
        List<String> actions = kind == Kind.PAGE ? List.of("platform.page_definition.create", "platform.presentation_variant.create", "platform.presentation_revision.create", "platform.presentation_publish.publish") : List.of("platform.menu.create");
        for (String action : actions) {
            int split = action.lastIndexOf('.');
            permissions.requireAuthorized(ActionExecutionContext.ofActionCode(action.substring(0, split), action.substring(split + 1), Set.of(), CurrentUserContext.currentUser()));
        }
    }
    private static final Set<String> SYSTEM_FIELDS = Set.of("id", "tenantId", "version", "deleted", "deletedAt", "deletedBy", "createdAt", "createdBy", "updatedAt", "updatedBy");
    private static List<String> checkedNames(List<String> names) {
        if (names == null || names.size() > 40 || names.stream().anyMatch(value -> value == null || !value.matches("[a-z][a-zA-Z0-9_]{0,63}")) || names.stream().distinct().count() != names.size())
            throw new IllegalArgumentException("页面字段必须来自实际目录，每个区域最多 40 项且不能重复");
        return List.copyOf(names);
    }
    private static String titles(List<String> names, Map<String, MetadataField> fields) { return String.join("、", names.stream().map(name -> fields.get(name).getTitle()).toList()); }
    private static void requireRequestId(String value) { if (value == null || !value.matches("[a-zA-Z0-9-]{16,80}")) throw new IllegalArgumentException("确认标识格式无效"); }
    private static String json(Object value) { try { return JSON.writeValueAsString(value); } catch (Exception error) { throw new IllegalArgumentException("建设参数无效", error); } }
    private static String digest(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception error) { throw new IllegalStateException(error); } }
}
