package net.ximatai.muyun.spring.platform.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.platform.save.RecordSaveReceiptService;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

/** Narrow bridge installed by MVC; ordinary saves retain their existing contract. */
public final class RecordSaveRequestSupport {
    public static final String HEADER = "X-Muyun-Save-Request";
    static final String ATTRIBUTE = RecordSaveRequestSupport.class.getName();
    private final RecordSaveReceiptService receipts;
    private final ObjectMapper mapper;

    public RecordSaveRequestSupport(RecordSaveReceiptService receipts, ObjectMapper mapper) {
        this.receipts = receipts;
        this.mapper = mapper;
    }

    public static <T extends EntityContract> T execute(CrudWeb<T, ?> controller,
            String action, String recordId, T payload, Supplier<T> save) {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes))
            return save.get();
        var request = attributes.getRequest();
        String requestId = request.getHeader(HEADER);
        if (requestId == null) return save.get();
        var support = (RecordSaveRequestSupport) request.getAttribute(ATTRIBUTE);
        if (support == null) throw new IllegalStateException("Reliable record saves are unavailable");
        Map<String, String> headers = new TreeMap<>();
        for (String name : java.util.List.of("X-MuYun-Tenant-Id", "X-MuYun-Page-Context",
                "X-MuYun-Page-Selection", "X-MuYun-Menu-Id")) {
            String value = request.getHeader(name);
            if (value != null) headers.put(name, value);
        }
        var envelope = support.mapper.createObjectNode();
        envelope.set("record", support.mapper.valueToTree(payload));
        envelope.put("recordId", recordId);
        envelope.set("scope", support.mapper.valueToTree(headers));
        String digest = RecordSaveReceiptService.digest(canonical(envelope));
        return support.receipts.execute(requestId, controller.webScopeName(), action, digest, save, controller::view);
    }

    public static SaveReceipt lookup(CrudWeb<?, ?> controller, String requestId) {
        var attributes = (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
        var support = (RecordSaveRequestSupport) attributes.getRequest().getAttribute(ATTRIBUTE);
        if (support == null) throw new IllegalStateException("Reliable record saves are unavailable");
        var receipt = support.receipts.lookup(requestId, controller.webScopeName());
        if (receipt == null) return new SaveReceipt(false, null, null, null);
        // Apply current field/record/menu/scope authorization before disclosing even the receipt identity.
        controller.view(receipt.getRecordId());
        return new SaveReceipt(true, receipt.getRecordId(), receipt.getRecordVersion(), receipt.getActionCode());
    }

    public record SaveReceipt(boolean committed, String recordId, Integer recordVersion, String actionCode) {}

    private static String canonical(JsonNode value) {
        if (value.isObject()) {
            Map<String, String> fields = new TreeMap<>();
            value.fields().forEachRemaining(entry -> fields.put(entry.getKey(), canonical(entry.getValue())));
            return fields.entrySet().stream().map(entry ->
                    new com.fasterxml.jackson.databind.node.TextNode(entry.getKey()).toString() + ":" + entry.getValue())
                    .collect(java.util.stream.Collectors.joining(",", "{", "}"));
        }
        if (value.isArray()) {
            java.util.List<String> items = new java.util.ArrayList<>();
            value.forEach(item -> items.add(canonical(item)));
            return String.join(",", items).transform(text -> "[" + text + "]");
        }
        return value.toString();
    }
}
