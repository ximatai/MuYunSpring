package net.ximatai.muyun.spring.platform.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.ximatai.muyun.spring.common.i18n.*;
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
        return WebListResponse.class.isAssignableFrom(parameter.getParameterType()) ||
                WebPageResponse.class.isAssignableFrom(parameter.getParameterType()) ||
                WorkflowRuntimeRenderBundle.class.isAssignableFrom(parameter.getParameterType()) ||
                WorkflowModuleTaskProcessBundle.class.isAssignableFrom(parameter.getParameterType()) ||
                WorkflowModuleTaskContinueResult.class.isAssignableFrom(parameter.getParameterType()) ||
                parameter.getContainingClass() == ModuleFieldOptionWebController.class;
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
        for (var slot : slots) {
            if (!(output.at(slot.parentPath()) instanceof ObjectNode parent)) continue;
            parent.set(slot.property() + "Text", mapper.valueToTree(TextPresentations.of(slot.source(), Map.of(), prepared, catalog)));
            parent.put(slot.property(), prepared.resolve(slot.source(), Map.of(), LanguageContext.currentTag()).text());
        }
        return mapper.convertValue(output, new TypeReference<Object>() { });
    }
    private void collect(Object value, String path, List<Slot> slots) {
        if (value instanceof WebListResponse<?> list) collect(list.records(), path + "/records", slots);
        else if (value instanceof WebPageResponse<?> page) collect(page.records(), path + "/records", slots);
        else if (value instanceof List<?> list) for (int i = 0; i < list.size(); i++) collect(list.get(i), path + "/" + i, slots);
        else if (value instanceof WebTreeNode<?> node) {
            collect(node.record(), path + "/record", slots); collect(node.children(), path + "/children", slots);
        } else if (value instanceof MenuNavigationView menu) {
            slots.add(new Slot(path, "title", ConfigurationTextSources.menuTitle(menu.id(), menu.title())));
        } else if (value instanceof OptionItem option && option.titleSource() != null) {
            slots.add(new Slot(path, "title", option.titleSource()));
        } else if (value instanceof WorkflowWorkbenchCard card && !Boolean.TRUE.equals(card.addedByAddSign()) &&
                card.workflowVersionId() != null && card.nodeKey() != null && card.nodeTitle() != null) {
            slots.add(new Slot(path, "nodeTitle", ConfigurationTextSources.workflowNodeTitle(card.workflowVersionId(), card.nodeKey(), card.nodeTitle())));
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
        } else if (value instanceof WorkflowModuleTaskProcessBundle process && process.workflowVersionId() != null) {
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
    private record Slot(String parentPath, String property, TextReference source) { }
}
