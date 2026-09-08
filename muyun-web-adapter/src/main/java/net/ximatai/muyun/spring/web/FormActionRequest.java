package net.ximatai.muyun.spring.web;

/** Current unsaved record, decoded through the same model contract as standard persistence. */
public record FormActionRequest<T>(T record) {
    public FormActionRequest {
        if (record == null) throw new IllegalArgumentException("form action requires current record");
    }
}
