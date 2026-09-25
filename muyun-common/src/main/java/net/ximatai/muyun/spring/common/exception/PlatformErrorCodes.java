package net.ximatai.muyun.spring.common.exception;

public final class PlatformErrorCodes {
    public static final String FILE_REFERENCE_ALREADY_BOUND = "FILE_REFERENCE_ALREADY_BOUND";
    public static final String AUTH_REQUIRED = "AUTH_REQUIRED";
    public static final String AUTH_EXPIRED = "AUTH_EXPIRED";
    public static final String PASSWORD_CHANGE_REQUIRED = "PASSWORD_CHANGE_REQUIRED";
    public static final String LOGIN_BAD_CREDENTIALS = "LOGIN_BAD_CREDENTIALS";
    public static final String ACCESS_DENIED = "ACCESS_DENIED";
    public static final String APPLICATION_NOT_OPENED = "APPLICATION_NOT_OPENED";
    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String CONFLICT_VERSION = "CONFLICT_VERSION";
    public static final String CONFLICT_UNIQUE = "CONFLICT_UNIQUE";
    public static final String RESOURCE_IN_USE = "RESOURCE_IN_USE";
    /**
     * A create command reused the identifier of a record retained by soft deletion.
     * The caller can recover the original resource through its business recycle bin.
     */
    public static final String RESOURCE_SOFT_DELETED_CONFLICT = "RESOURCE_SOFT_DELETED_CONFLICT";
    public static final String RESOURCE_NOT_FOUND = "RESOURCE_NOT_FOUND";
    public static final String CONFIG_MISSING = "CONFIG_MISSING";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private PlatformErrorCodes() {
    }
}
