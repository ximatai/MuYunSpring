package net.ximatai.muyun.spring.platform.attachment;

/** Browser-selected file facts exposed to business admission policy. */
public record FileReferenceUploadFile(String name, String mediaType, long sizeBytes) {
    public FileReferenceUploadFile {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("upload file name must not be blank");
        name = name.trim();
        mediaType = mediaType == null || mediaType.isBlank() ? null : mediaType.trim();
        if (sizeBytes < 0) throw new IllegalArgumentException("upload file size must not be negative");
    }
}
