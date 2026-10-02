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
import net.ximatai.muyun.spring.platform.runtime.DynamicRuntimeActivationService;
import net.ximatai.muyun.spring.platform.ui.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Requirements-bound entry and acceptance; page editing belongs to standard page governance. */
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
    private final PlatformPresentationRevisionResolver presentationResolver;
    private final MenuService menus;
    private final MenuSchemeService schemes;
    private final DynamicRuntimeActivationService activation;
    private final ActionExecutionPolicyService permissions;

    public ApplicationConstructionDeliveryService(net.ximatai.muyun.database.core.IDatabaseOperations<?> database, ApplicationConstructionPlanService plans, ApplicationConstructionFieldService fields,
            ApplicationConstructionDeliveryDao receipts, ApplicationConstructionAcceptanceDao acceptances, PlatformPageDefinitionService pages, PlatformPresentationVariantService variants,
            PlatformPresentationRevisionResolver presentationResolver, MenuService menus,
            MenuSchemeService schemes, DynamicRuntimeActivationService activation, ActionExecutionPolicyService permissions) {
        this.presentationResolver = Objects.requireNonNull(presentationResolver);
        this.database = Objects.requireNonNull(database); this.plans = Objects.requireNonNull(plans); this.fields = Objects.requireNonNull(fields); this.receipts = Objects.requireNonNull(receipts); this.acceptances = Objects.requireNonNull(acceptances); this.pages = Objects.requireNonNull(pages); this.variants = Objects.requireNonNull(variants);
        this.menus = Objects.requireNonNull(menus); this.schemes = Objects.requireNonNull(schemes); this.activation = Objects.requireNonNull(activation); this.permissions = Objects.requireNonNull(permissions);
    }
    public enum Kind { PAGE, ENTRY }
    public record Proposal(int planRevision, String objectKey, Kind kind, String title,
                           List<String> listFields, List<String> formFields, List<String> searchFields, Map<String, List<String>> childFields) {
        public Proposal(int planRevision, String objectKey, Kind kind, String title,
                        List<String> listFields, List<String> formFields, List<String> searchFields) {
            this(planRevision, objectKey, kind, title, listFields, formFields, searchFields, Map.of());
        }
        public Proposal {
            if (planRevision < 1 || kind == null || objectKey == null || !objectKey.matches("[a-z][a-z0-9_-]{0,63}"))
                throw new IllegalArgumentException("建设节点参数无效");
            if (title == null || title.isBlank() || title.length() > 120) throw new IllegalArgumentException("标题不能为空或过长");
            listFields = checkedNames(listFields); formFields = checkedNames(formFields); searchFields = checkedNames(searchFields);
            var children = new TreeMap<String, List<String>>();
            if (childFields != null) childFields.forEach((alias, names) -> {
                if (alias == null || !alias.matches("[a-z][a-z0-9_]{0,62}")) throw new IllegalArgumentException("明细标识无效");
                var checked = checkedNames(names);
                if (checked.isEmpty()) throw new IllegalArgumentException("明细至少展示一个字段");
                children.put(alias, checked);
            });
            if (children.size() > 16) throw new IllegalArgumentException("页面明细最多 16 组");
            childFields = Collections.unmodifiableMap(children);
            if (kind == Kind.PAGE && (listFields.isEmpty() || formFields.isEmpty())) throw new IllegalArgumentException("列表和表单至少需要一个字段");
            if (kind == Kind.ENTRY && (!listFields.isEmpty() || !formFields.isEmpty() || !searchFields.isEmpty() || !childFields.isEmpty()))
                throw new IllegalArgumentException("入口确认不包含页面配置");
        }
    }
    public record Preview(Proposal proposal, String moduleAlias, List<String> lines, String fingerprint) {}
    public record Command(String requestId, Proposal proposal, String fingerprint) {}
    public record Receipt(String requestId, String objectKey, int planRevision, Kind kind, String moduleAlias,
                          String pageId, String variantId, String revisionId, String menuId, int metadataVersion) {}
    public record Progress(String objectKey, String moduleAlias, String runtimeStatus, boolean pagePublished,
                           boolean entryVisible, String menuId, boolean needsReview, boolean acceptanceConfirmed, List<String> remainingWork, List<Receipt> receipts, List<ApplicationConstructionRequirements.Evidence> requirements) {
        /** Configuration progress never queries tenant business records or proves their absence. */
        @com.fasterxml.jackson.annotation.JsonProperty
        public String businessDataStatus() { return "NOT_QUERIED"; }
    }

    public Preview preview(String planId, Proposal proposal) {
        requireOperator();
        if (proposal == null) throw new IllegalArgumentException("建设节点参数无效");
        requireEntry(proposal.kind());
        requirePermissions();
        var plan = plans.read(planId);
        plan.requireOpen(proposal.objectKey());
        if (plan.revision() != proposal.planRevision()) throw new IllegalArgumentException("需求版本已变化，请重新预检");
        ApplicationConstructionRequirements.requireBuildable(plan.content(), proposal.objectKey());
        var description = fields.describe(planId, proposal.objectKey());
        if (ApplicationConstructionRequirements.missingConfiguration(fields.evidence(plan, proposal.objectKey(), description)))
            throw new IllegalArgumentException("已确认要求尚未落实到字段、关系或计算配置，请先补齐再发布页面或入口");
        var binding = fields.binding(plan, proposal.objectKey());
        try (var ignored = TenantContext.system("construction delivery preview")) {
            var lines = new ArrayList<String>();
            Object baseline;
                var currentPage = currentPage(plan, proposal.objectKey());
                if (currentPage == null) throw new IllegalArgumentException("请先完成页面发布");
                if (!pageCoversRequirements(plan.content(), proposal.objectKey(), currentPage.revision()))
                    throw new IllegalArgumentException("请先核对正式页面是否覆盖已确认需求");
                if (menus.currentUserVisibleModuleMenu(binding.moduleAlias()) != null)
                    throw new IllegalArgumentException("已有访问入口，请查询进度，不要重复创建");
                var scheme = schemes.resolveCurrentUserScheme(CurrentUserContext.currentUser().orElseThrow());
                if (scheme.getScopeType() != MenuScopeType.SYSTEM) throw new IllegalArgumentException("当前仅支持系统配置工作台入口");
                lines.add("在当前系统工作台菜单方案“" + scheme.getTitle() + "”下创建入口：" + proposal.title());
                lines.add("入口使用已发布页面，不授予角色或租户业务用户新的业务权限。");
                baseline = List.of(currentPage, scheme);
            lines.add("依据需求第 " + plan.revision() + " 版；业务可用性仍需按验收例子核对。");
            return new Preview(proposal, binding.moduleAlias(), List.copyOf(lines), digest(json(List.of(planId, proposal, description, baseline))));
        }
    }

    @Transactional
    public Receipt confirm(String planId, Command command) {
        requireOperator();
        if (command == null || command.proposal() == null || command.fingerprint() == null) throw new IllegalArgumentException("确认参数无效");
        requireEntry(command.proposal().kind());
        requirePermissions(); plans.read(planId); requireRequestId(command.requestId());
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
        var binding = fields.binding(plan, proposal.objectKey());
        try (var ignored = TenantContext.system("confirmed construction delivery")) {
            Receipt result;
                var scheme = schemes.resolveCurrentUserScheme(CurrentUserContext.currentUser().orElseThrow());
                var menu = new Menu(); menu.setSchemeId(scheme.getId()); menu.setParentId(TreeAbility.ROOT_ID);
                menu.setTitle(proposal.title()); menu.setModuleAlias(binding.moduleAlias()); menu.setEnabled(true);
                menu.setOpenMode(MenuOpenMode.TAB); menu.setPageMode(MenuPageMode.LIST);
                String menuId = menus.insert(menu);
                var currentPage = Objects.requireNonNull(currentPage(plan, proposal.objectKey()));
                result = new Receipt(command.requestId(), proposal.objectKey(), plan.revision(), Kind.ENTRY, binding.moduleAlias(), currentPage.page().getId(), currentPage.variant().getId(), currentPage.revision().getId(), menuId, fields.describe(planId, proposal.objectKey()).metadataVersion());
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
            var page = currentPage(plan, objectKey);
            boolean published = page != null;
            var menu = menus.currentUserVisibleModuleMenu(description.moduleAlias());
            boolean visible = menu != null && menu.getModuleAlias().equals(description.moduleAlias());
            boolean stale = page != null && !pageCoversRequirements(plan.content(), objectKey, page.revision());
            String runtime = activation.status(description.moduleAlias()).status();
            var remaining = new ArrayList<String>();
            if (!published) remaining.add("当前正式页面尚未发布或不可用");
            if (!visible) remaining.add("工作台入口尚不可见");
            if (stale) remaining.add("当前页面未覆盖已确认需求，请核对页面与验收范围");
            if (!"ACTIVE".equals(runtime)) remaining.add("模块运行态尚未激活");
            String baseline = acceptanceBaseline(plan, description, page, menu);
            var evidence = fields.evidence(plan, objectKey, description);
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
    public enum TaskAction { REVIEW_CURRENT_CONFIGURATION, REVIEW_REQUIREMENTS, INITIALIZE, VERIFY_RUNTIME, CONFIGURE_FIELDS, REVIEW_CONFIGURATION, PUBLISH_PAGE, CREATE_ENTRY, VERIFY_BUSINESS }
    public record TaskOption(TaskAction action, String explanation) {}
    public record TaskObject(String objectKey, String title, boolean complete, List<TaskOption> options,
                             List<ApplicationConstructionRequirements.Evidence> requirements, Progress progress) {}
    public record Task(int planRevision, List<TaskObject> objects) {}

    /** Recompute choices from current facts. No persisted cursor or prescribed order across objects. */
    public Task task(String planId) {
        requireOperator();
        var plan = plans.read(planId);
        var objects = new ArrayList<TaskObject>();
        for (var object : plan.content().objects()) {
            if (plan.deliveredObjectKeys().contains(object.key())) {
                objects.add(new TaskObject(object.key(), object.name(), true,
                        List.of(new TaskOption(TaskAction.REVIEW_CURRENT_CONFIGURATION, "已交付；后续改进读取当前低代码治理配置，历史方案仅供参考")), List.of(), null));
                continue;
            }
            boolean associated = plan.moduleBindings().stream().anyMatch(item -> item.objectKey().equals(object.key()));
            var evidence = ApplicationConstructionRequirements.evaluate(plan.content(), object.key(), List.of());
            var options = new ArrayList<TaskOption>();
            boolean complete = false;
            Progress progress;
            try { progress = associated ? progress(planId, object.key()) : null; }
            catch (IllegalArgumentException unavailable) {
                objects.add(new TaskObject(object.key(), object.name(), false,
                        List.of(new TaskOption(TaskAction.REVIEW_CONFIGURATION, unavailable.getMessage())), evidence, null));
                continue;
            }
            if (progress != null) evidence = progress.requirements();
            if (!associated) {
                options.add(new TaskOption(TaskAction.INITIALIZE, "先核对当前标准应用与模块目录；缺少时逐页准备可见表单并分别确认，已有对象直接进入标准治理，并将实际标准模块关联到方案对象；关联不要求完整需求映射"));
            } else if (ApplicationConstructionRequirements.blocked(evidence)) {
                options.add(new TaskOption(TaskAction.REVIEW_REQUIREMENTS, "此对象仍有未兑现要求，请说明阻断项；不能承诺下一步自动完成"));
                options.add(new TaskOption(TaskAction.REVIEW_CONFIGURATION, "已有配置保留，可核对当前成果；不要重复初始化或将局部成果视为完整交付"));
            } else {
                complete = progress.acceptanceConfirmed();
                if (!"ACTIVE".equals(progress.runtimeStatus())) {
                    options.add(new TaskOption(TaskAction.VERIFY_RUNTIME, "先核实已提交配置的可用状态，不重复创建"));
                } else if (!complete) {
                    boolean missing = ApplicationConstructionRequirements.missingConfiguration(evidence);
                    boolean sharedGovernance = evidence.stream().filter(item -> item.status() == ApplicationConstructionRequirements.Status.CONFIGURATION_MISSING)
                            .anyMatch(item -> plan.content().requirements().stream().anyMatch(requirement -> requirement.objectKey().equals(object.key())
                                    && requirement.section() == item.section() && requirement.index() == item.index()
                                    && requirement.fieldName().equals(item.fieldName())
                                    && (requirement.mode() == ApplicationConstructionRequirement.Mode.CHILD
                                    || requirement.mode() == ApplicationConstructionRequirement.Mode.CALCULATION || requirement.fieldName().contains("."))));
                    if (missing && !sharedGovernance)
                        options.add(new TaskOption(TaskAction.CONFIGURE_FIELDS, "通过标准元数据候选补齐登记内容，保留已有配置"));
                    if (progress.needsReview() || sharedGovernance)
                        options.add(new TaskOption(TaskAction.REVIEW_CONFIGURATION, sharedGovernance ? "通过标准元数据与规则治理补齐明细、引用和计算，保留已有配置" : "核对需求与配置变化，保留已生效成果"));
                    if (!missing) {
                        if (!progress.pagePublished())
                            options.add(new TaskOption(TaskAction.PUBLISH_PAGE, "依据实际字段准备录入和查询页面"));
                        if (progress.pagePublished() && !progress.needsReview()) {
                            if (!progress.entryVisible())
                                options.add(new TaskOption(TaskAction.CREATE_ENTRY, "核实已有入口后准备访问入口，不自动授权"));
                            else if (!plan.content().questions().isEmpty())
                                options.add(new TaskOption(TaskAction.REVIEW_REQUIREMENTS, "页面和入口已可用；最终验收前请收口方案未决问题，无需重复建设"));
                            else options.add(new TaskOption(TaskAction.VERIFY_BUSINESS, "页面和入口已可用，可以开始试用；核对实际效果后确认验收，无需重复建设"));
                        }
                    }
                }
            }
            objects.add(new TaskObject(object.key(), object.name(), complete, List.copyOf(options), evidence, progress));
        }
        return new Task(plan.revision(), List.copyOf(objects));
    }

    public record AcceptancePreview(String objectKey, int planRevision, List<String> checks, String fingerprint) {}
    public record AcceptanceCommand(String requestId, String objectKey, String fingerprint) {}
    public record AcceptanceReceipt(String requestId, String objectKey, int planRevision, String baseline) {}

    public AcceptancePreview previewAcceptance(String planId, String objectKey) {
        plans.read(planId).requireOpen(objectKey);
        var progress = progress(planId, objectKey);
        if (!progress.pagePublished() || !progress.entryVisible() || progress.needsReview() || !"ACTIVE".equals(progress.runtimeStatus()))
            throw new IllegalArgumentException("页面、入口或建设基线尚未就绪，不能确认验收");
        if (ApplicationConstructionRequirements.blocked(progress.requirements()) || ApplicationConstructionRequirements.missingConfiguration(progress.requirements()))
            throw new IllegalArgumentException("本期要求尚未全部具备配置证据或明确人工核验方式，不能确认验收");
        var plan = plans.read(planId);
        if (!plan.content().questions().isEmpty()) throw new IllegalArgumentException("方案仍有未决问题，请先澄清并重新确认范围");
        try (var ignored = TenantContext.system("construction acceptance preview")) {
            var description = fields.describe(planId, objectKey);
            var page = currentPage(plan, objectKey);
            var menu = menus.currentUserVisibleMenu(progress.menuId());
            var checks = new ArrayList<String>();
            checks.add("请实际验证以下需求，而非仅根据配置发布成功确认；未支持的要求应先修订方案。");
            for (var evidence : progress.requirements())
                checks.add((evidence.status() == ApplicationConstructionRequirements.Status.MANUAL_RESPONSIBILITY ? "已约定由人处理，验收时核对实际执行：" : "配置证据匹配，仍须业务试用：")
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
                        || item.mode() == ApplicationConstructionRequirement.Mode.UNIQUE
                        || item.mode() == ApplicationConstructionRequirement.Mode.REFERENCE
                        || item.mode() == ApplicationConstructionRequirement.Mode.CALCULATION)
                .map(ApplicationConstructionRequirement::fieldName).distinct().toList();
    }
    private static List<String> requiredChildren(ApplicationConstructionPlanContent content, String objectKey) {
        return content.requirements().stream().filter(item -> item.objectKey().equals(objectKey)
                        && item.mode() == ApplicationConstructionRequirement.Mode.CHILD)
                .map(ApplicationConstructionRequirement::fieldName).distinct().toList();
    }
    private boolean pageCoversRequirements(ApplicationConstructionPlanContent content, String objectKey, PlatformPresentationRevision revision) {
        try {
            var formFields = new HashSet<String>();
            var childRelations = new HashSet<String>();
            for (var node : JSON.readTree(revision.getUiTreeJson()).path("nodes"))
                if ("form".equals(node.path("slot").asText())) {
                    addPlacedFields(node, "", formFields);
                    for (var child : node.path("relations")) {
                        String alias = child.path("relation").asText();
                        childRelations.add(alias);
                        addPlacedFields(child, alias + ".", formFields);
                    }
                }
            return formFields.containsAll(requiredInputs(content, objectKey)) && childRelations.containsAll(requiredChildren(content, objectKey));
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            return false;
        }
    }
    private static void addPlacedFields(com.fasterxml.jackson.databind.JsonNode node, String prefix, Set<String> fields) {
        node.path("fields").forEach(field -> fields.add(prefix + (field.isTextual() ? field.asText() : field.path("field").asText())));
        node.path("groups").forEach(group -> addPlacedFields(group, prefix, fields));
    }
    private record CurrentPage(PlatformPageDefinition page, PlatformPresentationVariant variant, PlatformPresentationRevision revision) {}
    /** Receipts prove past writes; standard resolution determines what is effective now. */
    private CurrentPage currentPage(ApplicationConstructionPlanService.Snapshot plan, String objectKey) {
        var binding = fields.binding(plan, objectKey);
        var page = pages.resolveGlobalPage(binding.moduleAlias(), "management").orElse(null);
        if (page == null || !Objects.equals(page.getMainRelationId(), binding.relationId())
                || page.getContractType() != PlatformPageContractType.MANAGEMENT) return null;
        var revision = presentationResolver.resolve(page.getId(), PlatformPresentationClientType.WEB, null, null).orElse(null);
        return revision == null ? null : new CurrentPage(page, variants.select(revision.getVariantId()), revision);
    }
    private String acceptanceBaseline(ApplicationConstructionPlanService.Snapshot plan, ApplicationConstructionFieldService.Description description, CurrentPage page, Menu menu) {
        return digest(json(Arrays.asList(plan.planId(), plan.revision(), description, page, menu)));
    }
    private void lockBaseline(String planId, String objectKey) {
        var binding = fields.binding(plans.read(planId), objectKey);
        database.query("select id from platform_metadata where id in (select metadata_id from platform_module_metadata_relation where module_alias = ?) order by id for update", binding.moduleAlias());
        database.query("select id from platform_module_metadata_relation where module_alias = ? order by id for update", binding.moduleAlias());
        try (var ignored = TenantContext.system("lock current construction delivery")) {
            var page = currentPage(plans.read(planId), objectKey);
            if (page != null) {
                database.query("select id from platform_page_definition where id = ? for update", page.page().getId());
                database.query("select id from platform_presentation_variant where id = ? for update", page.variant().getId());
            }
            var menu = menus.currentUserVisibleModuleMenu(binding.moduleAlias());
            if (menu != null) database.query("select id from platform_menu where id = ? for update", menu.getId());
        }
    }
    public static Receipt receipt(ApplicationConstructionDelivery stored) {
        try { return JSON.readValue(stored.getReceiptJson(), Receipt.class); }
        catch (Exception error) { throw new IllegalStateException("建设回执无法读取", error); }
    }
    private void requireOperator() {
        if (!CurrentUserContext.currentUser().map(user -> user.system()).orElse(false)) throw new PlatformAccessDeniedException("页面与入口建设要求系统配置身份");
    }
    private static void requireEntry(Kind kind) {
        if (kind != Kind.ENTRY) throw new IllegalArgumentException("页面建设已统一到标准页面编排，请读取并确认共享页面候选；历史结果仅支持查询");
    }
    private void requirePermissions() {
        permissions.requireAuthorized(ActionExecutionContext.ofActionCode("platform.menu", "create", Set.of(), CurrentUserContext.currentUser()));
    }
    private static List<String> checkedNames(List<String> names) {
        if (names == null || names.size() > 40 || names.stream().anyMatch(value -> value == null || !value.matches("[a-z][a-zA-Z0-9_]{0,63}")) || names.stream().distinct().count() != names.size())
            throw new IllegalArgumentException("页面字段必须来自实际目录，每个区域最多 40 项且不能重复");
        return List.copyOf(names);
    }
    private static void requireRequestId(String value) { if (value == null || !value.matches("[a-zA-Z0-9-]{16,80}")) throw new IllegalArgumentException("确认标识格式无效"); }
    private static String json(Object value) { try { return JSON.writeValueAsString(value); } catch (Exception error) { throw new IllegalArgumentException("建设参数无效", error); } }
    private static String digest(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception error) { throw new IllegalStateException(error); } }
}
