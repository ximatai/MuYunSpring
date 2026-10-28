package net.ximatai.muyun.spring.platform.attachment;

import net.ximatai.muyun.spring.common.exception.PlatformException;

/** Sanitized failure. Neither an HTTP status nor a timeout proves that a write did not occur. */
public final class FileReceptionException extends PlatformException {
    public enum Kind { TRANSPORT, HTTP, PROTOCOL }
    private final Kind kind;
    private final Integer upstreamHttpStatus;

    public FileReceptionException(Kind kind, Integer upstreamHttpStatus) {
        super("file_reception_failed", 502, "file reception request failed: " + kind + (upstreamHttpStatus == null ? "" : " (HTTP " + upstreamHttpStatus + ")"));
        this.kind = kind;
        this.upstreamHttpStatus = upstreamHttpStatus;
    }
    public Kind kind() { return kind; }
    public Integer upstreamHttpStatus() { return upstreamHttpStatus; }
}
