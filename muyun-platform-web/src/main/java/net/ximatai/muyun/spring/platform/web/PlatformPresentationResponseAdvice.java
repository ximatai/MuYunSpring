package net.ximatai.muyun.spring.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.ximatai.muyun.spring.common.i18n.*;
import net.ximatai.muyun.spring.platform.application.ApplicationConstructionDeliveryService;
import net.ximatai.muyun.spring.platform.metadata.*;
import net.ximatai.muyun.spring.platform.application.ApplicationConstructionRequirements;
import net.ximatai.muyun.spring.platform.application.ApplicationConstructionText;
import net.ximatai.muyun.spring.common.option.OptionItem;
import net.ximatai.muyun.spring.platform.localization.ConfigurationTextSources;
import net.ximatai.muyun.spring.platform.workflow.WorkflowRuntimeRenderBundle;
import net.ximatai.muyun.spring.platform.workflow.WorkflowModuleTaskProcessBundle;
import net.ximatai.muyun.spring.platform.workflow.WorkflowModuleTaskContinueResult;
import net.ximatai.muyun.spring.platform.workflow.WorkflowAdminInstanceView;
import net.ximatai.muyun.spring.platform.workflow.WorkflowAdminActiveTaskView;
import net.ximatai.muyun.spring.platform.workflow.WorkflowTaskGuide;
import net.ximatai.muyun.spring.platform.workflow.WorkflowWorkbenchCard;
import net.ximatai.muyun.spring.platform.workflow.WorkflowEvent;
import net.ximatai.muyun.spring.platform.workflow.WorkflowHistoryEventView;
import net.ximatai.muyun.spring.platform.workflow.WorkflowSubmitStatusView;
import net.ximatai.muyun.spring.web.WebListResponse;
import net.ximatai.muyun.spring.web.WebPageResponse;
import net.ximatai.muyun.spring.web.WebTreeNode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Typed display projections only; configuration editors and arbitrary business records are excluded. */
@RestControllerAdvice
public class PlatformPresentationResponseAdvice implements ResponseBodyAdvice<Object> {
    private final ObjectMapper mapper;
    private final ObjectProvider<TextResolver> resolvers;
    private final ObjectProvider<LanguageCatalog> catalogs;
    public PlatformPresentationResponseAdvice(ObjectMapper mapper, ObjectProvider<TextResolver> resolvers, ObjectProvider<LanguageCatalog> catalogs) {
        this.mapper = mapper; this.resolvers = resolvers; this.catalogs = catalogs;
    }
    @Override public boolean supports(MethodParameter parameter, Class<? extends HttpMessageConverter<?>> converter) {
        return net.ximatai.muyun.spring.platform.config.LowCodeConfigHealthReport.class.isAssignableFrom(parameter.getParameterType()) ||
                net.ximatai.muyun.spring.platform.config.LowCodePackageDryRunResult.class.isAssignableFrom(parameter.getParameterType()) ||
                net.ximatai.muyun.spring.platform.config.LowCodeModulePackageImportDraft.class.isAssignableFrom(parameter.getParameterType()) ||
                net.ximatai.muyun.spring.platform.config.LowCodeModuleConfigArchiveResult.class.isAssignableFrom(parameter.getParameterType()) ||
                net.ximatai.muyun.spring.platform.code.CodeConfigurationService.Target.class.isAssignableFrom(parameter.getParameterType()) ||
                org.springframework.core.ResolvableType.forMethodParameter(parameter).getGeneric(0).resolve() == net.ximatai.muyun.spring.platform.code.CodeConfigurationService.Target.class ||
                parameter.getContainingClass() == PlatformModuleRuntimeActionWebController.class ||
                org.springframework.core.ResolvableType.forMethodParameter(parameter).getGeneric(0).resolve() == OpenApiModuleCatalogItem.class ||
                net.ximatai.muyun.spring.ability.query.QuerySchema.class.isAssignableFrom(parameter.getParameterType()) ||
                net.ximatai.muyun.spring.platform.module.ModuleTitleCatalog.class.isAssignableFrom(parameter.getParameterType()) || WebListResponse.class.isAssignableFrom(parameter.getParameterType()) ||
                WebPageResponse.class.isAssignableFrom(parameter.getParameterType()) ||
                WorkflowRuntimeRenderBundle.class.isAssignableFrom(parameter.getParameterType()) ||
                WorkflowSubmitStatusView.class.isAssignableFrom(parameter.getParameterType()) ||
                WorkflowModuleTaskProcessBundle.class.isAssignableFrom(parameter.getParameterType()) ||
                WorkflowModuleTaskContinueResult.class.isAssignableFrom(parameter.getParameterType()) ||
                parameter.getContainingClass() == ModuleFieldOptionWebController.class ||
                parameter.getContainingClass() == ApplicationConstructionPlanWebController.class ||
                parameter.getContainingClass() == PlatformBusinessRuleGovernanceWebController.class ||
                ModuleMetadataCapabilitySnapshot.class.isAssignableFrom(parameter.getParameterType()) ||
                MetadataModelChangeSetPreview.class.isAssignableFrom(parameter.getParameterType()) ||
                MetadataRelationChangeSetPreview.class.isAssignableFrom(parameter.getParameterType()) ||
                MetadataModelChangeSetPublishResult.class.isAssignableFrom(parameter.getParameterType());
    }
    @Override public Object beforeBodyWrite(Object body, MethodParameter parameter, MediaType contentType,
            Class<? extends HttpMessageConverter<?>> converter, ServerHttpRequest request, ServerHttpResponse response) { return project(body); }

    Object project(Object body) {
        var resolver = resolvers.getIfAvailable();
        var catalog = catalogs.getIfAvailable();
        if (body == null || resolver == null || catalog == null) return body;
        if (body instanceof List<?> list && list.stream().allMatch(OptionItem.class::isInstance)) {
            var options = list.stream().map(OptionItem.class::cast).toList();
            var prepared = resolver.prepare(options.stream().map(OptionItem::titleSource).filter(java.util.Objects::nonNull).toList());
            return options.stream().map(option -> option.titleSource() == null ? option : new OptionItem(
                    option.code(), prepared.resolve(option.titleSource(), Map.of(), LanguageContext.currentTag()).text(),
                    option.enabled(), option.sortOrder(), option.parentCode(), option.titleSource(),
                    TextPresentations.of(option.titleSource(), Map.of(), prepared, catalog))).toList();
        }
        List<Slot> slots = new ArrayList<>();
        collect(body, "", slots);
        if (slots.isEmpty()) return body;
        var prepared = resolver.prepare(slots.stream().map(Slot::source).toList());
        var output = mapper.valueToTree(body);
        Map<String, TextPresentation> constructionBindings = new java.util.LinkedHashMap<>();
        for (var slot : slots) {
            if (slot.resourceOnly()) {
                String path = slot.parentPath() + "/" + slot.property();
                // Only producer-declared slots that still match their original display facts are projected.
                if (output.at(path).isTextual() && output.at(path).asText().equals(TextResources.format(slot.source().originalText(), slot.arguments())))
                    constructionBindings.put(path, TextPresentations.of(slot.source(), slot.arguments(), prepared, catalog));
                continue;
            }
            if (!(output.at(slot.parentPath()) instanceof ObjectNode parent)) continue;
            if (slot.retainOriginal() && (!parent.path(slot.property()).isTextual() ||
                    !parent.path(slot.property()).asText().equals(TextResources.format(slot.source().originalText(), slot.arguments())) ||
                    (slot.source().sourceKind() == TextReference.SourceKind.CODE &&
                    !slot.source().sourceVersion().equals(TextReference.versionOf(slot.source().originalText()))))) continue;
            parent.set(slot.property() + "Text", mapper.valueToTree(TextPresentations.of(slot.source(), slot.arguments(), prepared, catalog)));
            if (!slot.retainOriginal())
                parent.put(slot.property(), prepared.resolve(slot.source(), slot.arguments(), LanguageContext.currentTag()).text());
        }
        if (!constructionBindings.isEmpty() && output instanceof ObjectNode object)
            object.set("textBindings", mapper.valueToTree(constructionBindings));
        // Preserve the JSON projection type: List<Map> would still be serialized with the controller's DTO element type.
        return output;
    }
    private void collect(Object value, String path, List<Slot> slots) {
        if (value instanceof net.ximatai.muyun.spring.platform.config.LowCodeConfigHealthReport report) {
            collect(report.items(), path + "/items", slots);
        } else if (value instanceof net.ximatai.muyun.spring.platform.config.LowCodeConfigHealthItem item) {
            diagnosticSlot(path, "message", item.textSource(), item.messageArgs(), slots);
            diagnosticSlot(path, "suggestion", item.suggestionSource(), item.suggestionArgs(), slots);
        } else if (value instanceof net.ximatai.muyun.spring.platform.config.LowCodePackageImportConflict conflict) {
            diagnosticSlot(path, "message", conflict.textSource(), conflict.messageArgs(), slots);
        } else if (value instanceof net.ximatai.muyun.spring.platform.config.LowCodePackageDependencyDiagnostic diagnostic) {
            diagnosticSlot(path, "message", diagnostic.textSource(), diagnostic.messageArgs(), slots);
        } else if (value instanceof net.ximatai.muyun.spring.platform.config.LowCodePackageDryRunResult result) {
            collect(result.healthReport(), path + "/healthReport", slots);
            collect(result.conflicts(), path + "/conflicts", slots);
        } else if (value instanceof net.ximatai.muyun.spring.platform.config.LowCodeModulePackageImportDraft draft) {
            collect(draft.dryRunResult(), path + "/dryRunResult", slots);
        } else if (value instanceof net.ximatai.muyun.spring.platform.config.LowCodeModuleConfigArchiveResult result) {
            collect(result.healthReport(), path + "/healthReport", slots);
        } else collectPlatform(value, path, slots);
    }

    private static void diagnosticSlot(String path, String property, TextReference source,
                                       Map<String, Object> arguments, List<Slot> slots) {
        if (source != null && source.sourceKind() == TextReference.SourceKind.CODE)
            slots.add(new Slot(path, property, source, arguments, false, true));
    }

    private void collectPlatform(Object value, String path, List<Slot> slots) {
        if (value instanceof MetadataModelChangeSetPreview preview) {
            collect(preview.relationPreviews(), path + "/relationPreviews", slots);
            collect(preview.warnings(), path + "/warnings", slots);
            collect(preview.errors(), path + "/errors", slots);
        } else if (value instanceof MetadataRelationChangeSetPreview preview) {
            collect(preview.warnings(), path + "/warnings", slots);
            collect(preview.errors(), path + "/errors", slots);
        } else if (value instanceof MetadataModelChangeSetPublishResult result) {
            collect(result.validatedPreview(), path + "/validatedPreview", slots);
            collect(result.snapshots(), path + "/snapshots", slots);
        } else if (value instanceof MetadataChangeSetValidationIssue issue) {
            if (issue.textSource() != null && issue.textSource().sourceKind() == TextReference.SourceKind.CODE)
                slots.add(new Slot(path, "message", issue.textSource(), issue.messageArgs(), false, true));
        } else if (value instanceof WorkflowSubmitStatusView status) {
            if (status.errorMessageSource() != null)
                slots.add(new Slot(path, "errorMessage", status.errorMessageSource(), status.errorMessageArgs(), false, true));
        } else if (value instanceof PlatformRecordActionAvailability availability) {
            collect(availability.actions(), path + "/actions", slots);
        } else if (value instanceof PlatformRecordActionAvailability.Action action) {
            if (action.reasonSource() != null)
                slots.add(new Slot(path, "reason", action.reasonSource(), Map.of(), false, true));
        } else if (value instanceof net.ximatai.muyun.spring.ability.query.QuerySchema schema) {
            collect(schema.fields(), path + "/fields", slots);
            collect(schema.quickSearch().fieldSchemas(), path + "/quickSearch/fieldSchemas", slots);
        } else if (value instanceof net.ximatai.muyun.spring.ability.query.QuerySchema.Field field) {
            if (field.titleSource() != null && field.title().equals(field.titleSource().originalText()))
                slots.add(new Slot(path, "title", field.titleSource()));
            collect(field.persistentControl(), path + "/persistentControl", slots);
        } else if (value instanceof net.ximatai.muyun.spring.ability.query.QuerySchema.PersistentControl control) {
            if (control.titleSource() != null && control.title().equals(control.titleSource().originalText()))
                slots.add(new Slot(path, "title", control.titleSource()));
        } else if (value instanceof net.ximatai.muyun.spring.platform.code.CodeConfigurationService.Target target) {
            if (target.moduleTitleSource() != null)
                slots.add(new Slot(path, "moduleTitle", target.moduleTitleSource(), Map.of(), false, true));
            collect(target.sources(), path + "/sources", slots);
        } else if (value instanceof net.ximatai.muyun.spring.platform.code.CodeConfigurationService.Source source) {
            if (source.titleSource() != null && source.title().equals(source.titleSource().originalText()))
                slots.add(new Slot(path, "title", source.titleSource(), Map.of(), false, true));
        } else if (value instanceof OpenApiModuleCatalogItem item) {
            if (item.titleSource() != null && item.title().equals(item.titleSource().originalText()))
                slots.add(new Slot(path, "title", item.titleSource()));
        } else if (value instanceof ModuleMetadataCapabilitySnapshot snapshot) {
            collect(snapshot.capabilities(), path + "/capabilities", slots);
        } else if (value instanceof ModuleMetadataCapabilityFact fact) {
            fact.textSources().forEach((property, source) ->
                    slots.add(new Slot(path, property, source, Map.of(), false, true)));
        } else if (value instanceof BusinessRuleGovernanceSnapshot snapshot) {
            collect(snapshot.functions(), path + "/functions", slots);
        } else if (value instanceof BusinessRuleIssue issue) {
            if (issue.textSource() != null && issue.textSource().sourceKind() == TextReference.SourceKind.CODE)
                slots.add(new Slot(path, "message", issue.textSource(), issue.messageArgs(), false, true));
        } else if (value instanceof BusinessRulePreview preview) {
            collect(preview.errors(), path + "/errors", slots);
            collect(preview.snapshot(), path + "/snapshot", slots);
        } else if (value instanceof BusinessRuleTrialResult trial) {
            collect(trial.errors(), path + "/errors", slots);
            collect(trial.preview(), path + "/preview", slots);
        } else if (value instanceof BusinessRuleApplyResult applied) {
            collect(applied.snapshot(), path + "/snapshot", slots);
            collect(applied.preview(), path + "/preview", slots);
        } else if (value instanceof BusinessRuleFunction function) {
            function.textSources().forEach((property, source) ->
                    slots.add(new Slot(path, property, source, Map.of(), false, true)));
            for (int i = 0; i < function.parameters().size(); i++) {
                var parameter = function.parameters().get(i);
                if (parameter.descriptionSource() != null)
                    slots.add(new Slot(path + "/parameters/" + i, "description", parameter.descriptionSource(), Map.of(), false, true));
            }
        } else if (value instanceof ApplicationConstructionDeliveryService.Task task) {
            for (int i = 0; i < task.objects().size(); i++) {
                var object = task.objects().get(i); String objectPath = path + "/objects/" + i;
                for (int j = 0; j < object.options().size(); j++)
                    constructionSlot(objectPath + "/options/" + j, "explanation", object.options().get(j).explanationSource(), slots);
                constructionEvidence(object.requirements(), objectPath + "/requirements", slots);
                if (object.progress() != null)
                    constructionList(objectPath + "/progress/remainingWork", object.progress().remainingWorkSources(), slots);
            }
            constructionEvidence(task.unmappedRequirements(), path + "/unmappedRequirements", slots);
        } else if (value instanceof ApplicationConstructionDeliveryService.Progress progress) {
            constructionList(path + "/remainingWork", progress.remainingWorkSources(), slots);
            constructionEvidence(progress.requirements(), path + "/requirements", slots);
        } else if (value instanceof ApplicationConstructionDeliveryService.AcceptancePreview preview) {
            constructionList(path + "/checks", preview.checkSources(), slots);
        } else if (value instanceof WebListResponse<?> list) collect(list.records(), path + "/records", slots);
        else if (value instanceof WebPageResponse<?> page) {
            collect(page.records(), path + "/records", slots);
            if (page.navigation() instanceof Map<?, ?> navigation && navigation.get("moduleTitles") instanceof net.ximatai.muyun.spring.platform.module.ModuleTitleCatalog titles)
                collect(titles, path + "/navigation/moduleTitles", slots);
        } else if (value instanceof net.ximatai.muyun.spring.platform.module.ModuleTitleCatalog titles) {
            titles.modules().forEach((alias, title) -> collect(title, path + "/modules/" + alias.replace("~", "~0").replace("/", "~1"), slots));
        } else if (value instanceof net.ximatai.muyun.spring.platform.module.ModuleTitleView title && title.titleSource() != null) {
            slots.add(new Slot(path, "title", title.titleSource()));
        }
        else if (value instanceof List<?> list) for (int i = 0; i < list.size(); i++) collect(list.get(i), path + "/" + i, slots);
        else if (value instanceof WebTreeNode<?> node) {
            collect(node.record(), path + "/record", slots); collect(node.children(), path + "/children", slots);
        } else if (value instanceof MenuNavigationView menu) {
            slots.add(new Slot(path, "title", ConfigurationTextSources.menuTitle(menu.id(), menu.title())));
        } else if (value instanceof OptionItem option && option.titleSource() != null) {
            slots.add(new Slot(path, "title", option.titleSource()));
        } else if (value instanceof WorkflowWorkbenchCard card) {
            if (!Boolean.TRUE.equals(card.addedByAddSign()) && card.workflowVersionId() != null && card.nodeKey() != null && card.nodeTitle() != null)
                slots.add(new Slot(path, "nodeTitle", ConfigurationTextSources.workflowNodeTitle(card.workflowVersionId(), card.nodeKey(), card.nodeTitle())));
            if (card.business() != null && card.business().moduleTitleSource() != null)
                slots.add(new Slot(path + "/business", "moduleTitle", card.business().moduleTitleSource()));
        } else if (value instanceof WorkflowEvent event) {
            frozenMessage(path, event.getMessage(), event.getMessageSourceText(), slots);
        } else if (value instanceof WorkflowHistoryEventView event) {
            frozenMessage(path, event.message(), event.messageSourceText(), slots);
        } else if (value instanceof WorkflowModuleTaskContinueResult result) {
            collect(result.processBundle(), path + "/processBundle", slots);
        } else if (value instanceof WorkflowAdminInstanceView instance && instance.workflowVersionId() != null) {
            for (int i = 0; i < instance.activeNodeLabels().size(); i++) {
                var label = instance.activeNodeLabels().get(i);
                if (!label.addedByAddSign() && label.title() != null)
                    slots.add(new Slot(path + "/activeNodeLabels/" + i, "title", ConfigurationTextSources.workflowNodeTitle(instance.workflowVersionId(), label.nodeKey(), label.title())));
            }
        } else if (value instanceof WorkflowAdminActiveTaskView task && task.workflowVersionId() != null && !Boolean.TRUE.equals(task.addedByAddSign())) {
            slots.add(new Slot(path, "nodeTitle", ConfigurationTextSources.workflowNodeTitle(task.workflowVersionId(), task.nodeKey(), task.nodeTitle())));
        } else if (value instanceof WorkflowModuleTaskProcessBundle process && hasText(process.workflowVersionId()) && hasText(process.nodeKey())) {
            var evaluation = process.evaluation();
            if (hasText(evaluation.failureCheckKey()) && hasText(evaluation.failureMessage()))
                slots.add(new Slot(path + "/evaluation", "failureMessage", ConfigurationTextSources.workflowCheckText(
                        process.workflowVersionId(), process.nodeKey(), evaluation.failureCheckKey(), "failureMessage", evaluation.failureMessage())));
            for (int i = 0; i < evaluation.checkTexts().size(); i++) {
                var text = evaluation.checkTexts().get(i);
                if (!hasText(text.checkKey())) continue;
                var textPath = path + "/evaluation/checkTexts/" + i;
                if (hasText(text.title()))
                    slots.add(new Slot(textPath, "title", ConfigurationTextSources.workflowCheckText(
                            process.workflowVersionId(), process.nodeKey(), text.checkKey(), "title", text.title())));
                if (hasText(text.failureMessage()))
                    slots.add(new Slot(textPath, "failureMessage", ConfigurationTextSources.workflowCheckText(
                            process.workflowVersionId(), process.nodeKey(), text.checkKey(), "failureMessage", text.failureMessage())));
            }
            for (int i = 0; i < evaluation.checkResults().size(); i++) {
                var check = evaluation.checkResults().get(i);
                if (!hasText(check.getCheckKey())) continue;
                var checkPath = path + "/evaluation/checkResults/" + i;
                if (hasText(check.getCheckTitle()))
                    slots.add(new Slot(checkPath, "checkTitle", ConfigurationTextSources.workflowCheckText(
                            process.workflowVersionId(), process.nodeKey(), check.getCheckKey(), "title", check.getCheckTitle())));
                if (hasText(check.getFailureMessage()))
                    slots.add(new Slot(checkPath, "failureMessage", ConfigurationTextSources.workflowCheckText(
                            process.workflowVersionId(), process.nodeKey(), check.getCheckKey(), "failureMessage", check.getFailureMessage())));
            }
            for (int i = 0; i < process.evaluation().guides().size(); i++)
                guideSources(process, process.evaluation().guides().get(i), path + "/evaluation/guides/" + i, slots);
            if (process.nextGuide() != null) guideSources(process, process.nextGuide(), path + "/nextGuide", slots);
        } else if (value instanceof WorkflowRuntimeRenderBundle bundle && bundle.instance() != null && bundle.instance().getWorkflowVersionId() != null) {
            for (int i = 0; i < bundle.nodes().size(); i++) {
                var node = bundle.nodes().get(i);
                if (!Boolean.TRUE.equals(node.getAddedByAddSign()) && node.getNodeTitle() != null)
                    slots.add(new Slot(path + "/nodes/" + i, "nodeTitle", ConfigurationTextSources.workflowNodeTitle(bundle.instance().getWorkflowVersionId(), node.getNodeKey(), node.getNodeTitle())));
            }
        }
    }
    private static boolean hasText(String value) { return value != null && !value.isBlank(); }

    private void guideSources(WorkflowModuleTaskProcessBundle process, WorkflowTaskGuide guide, String path, List<Slot> slots) {
        if (guide.getTitle() != null && !guide.getTitle().isBlank())
            slots.add(new Slot(path, "title", ConfigurationTextSources.workflowGuideText(process.workflowVersionId(), process.nodeKey(), guide.getGuideKey(), "title", guide.getTitle())));
        if (guide.getGuideConfigText() == null || guide.getGuideConfigText().isBlank()) return;
        try {
            var instruction = mapper.readTree(guide.getGuideConfigText()).path("instruction");
            if (instruction.isTextual() && !instruction.asText().isBlank())
                slots.add(new Slot(path, "instruction", ConfigurationTextSources.workflowGuideText(process.workflowVersionId(), process.nodeKey(), guide.getGuideKey(), "instruction", instruction.asText())));
        } catch (Exception ignored) { /* Keep unverified authored configuration unchanged. */ }
    }
    private void frozenMessage(String path, String original, String declaration, List<Slot> slots) {
        if (declaration == null || original == null) return;
        try {
            var source = mapper.readValue(declaration, TextReference.class);
            if (source.sourceKind() == TextReference.SourceKind.CODE && source.originalText().equals(original) &&
                    source.sourceVersion().equals(TextReference.versionOf(original)) && source.sourceId().endsWith("@" + source.sourceVersion()))
                slots.add(new Slot(path, "message", source));
        } catch (Exception ignored) { /* Unverified declarations retain their frozen original. */ }
    }
    private static void constructionEvidence(List<ApplicationConstructionRequirements.Evidence> evidence, String path, List<Slot> slots) {
        if (evidence == null) return;
        for (int i = 0; i < evidence.size(); i++) constructionSlot(path + "/" + i, "explanation", evidence.get(i).explanationSource(), slots);
    }
    private static void constructionList(String path, List<ApplicationConstructionText> texts, List<Slot> slots) {
        if (texts == null) return;
        for (int i = 0; i < texts.size(); i++) constructionSlot(path, String.valueOf(i), texts.get(i), slots);
    }
    private static void constructionSlot(String path, String property, ApplicationConstructionText text, List<Slot> slots) {
        if (text != null && text.source().sourceKind() == TextReference.SourceKind.CODE && text.source().sourceId().startsWith("platform.construction.") &&
                text.source().sourceVersion().equals(TextReference.versionOf(text.source().originalText())))
            slots.add(new Slot(path, property, text.source(), text.arguments(), true));
    }
    private record Slot(String parentPath, String property, TextReference source, Map<String, ?> arguments, boolean resourceOnly, boolean retainOriginal) {
        Slot(String parentPath, String property, TextReference source, Map<String, ?> arguments, boolean resourceOnly) {
            this(parentPath, property, source, arguments, resourceOnly, false);
        }
        Slot(String parentPath, String property, TextReference source) { this(parentPath, property, source, Map.of(), false); }
    }
}
