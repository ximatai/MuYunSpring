package net.ximatai.muyun.spring.common.util;

public final class PlatformNameRules {
    public static final int IDENTIFIER_MAX_LENGTH = 63;
    public static final int MODULE_ALIAS_MAX_LENGTH = 128;

    public static final String IDENTIFIER_PATTERN = "[a-z][a-z0-9_]{0,62}";
    private static final String CODE_PATTERN = "[A-Za-z0-9][A-Za-z0-9_\\-]{0,62}";
    private static final String FIELD_NAME_PATTERN = "[a-z][A-Za-z0-9]{0,62}";
    private static final String MODULE_ALIAS_SEGMENT_PATTERN = IDENTIFIER_PATTERN;
    public static final String MODULE_ALIAS_PATTERN = MODULE_ALIAS_SEGMENT_PATTERN
            + "(\\." + MODULE_ALIAS_SEGMENT_PATTERN + ")+";

    private PlatformNameRules() {
    }

    public static String requireApplicationAlias(String value) {
        requireText(value, "applicationAlias");
        if (!isIdentifier(value)) {
            throw new IllegalArgumentException("invalid applicationAlias: " + value);
        }
        return value;
    }

    public static String requireModuleAlias(String value) {
        requireText(value, "moduleAlias");
        if (value.length() > MODULE_ALIAS_MAX_LENGTH || !value.matches(MODULE_ALIAS_PATTERN)) {
            throw new IllegalArgumentException("invalid moduleAlias: " + value);
        }
        return value;
    }

    public static String requireModuleAliasInApplication(String moduleAlias, String applicationAlias) {
        String validApplicationAlias = requireApplicationAlias(applicationAlias);
        String validModuleAlias = requireModuleAlias(moduleAlias);
        String prefix = validApplicationAlias + ".";
        if (!validModuleAlias.startsWith(prefix) || validModuleAlias.length() == prefix.length()) {
            throw new IllegalArgumentException("moduleAlias must start with applicationAlias: " + moduleAlias);
        }
        return validModuleAlias;
    }

    public static String applicationAliasOfModuleAlias(String moduleAlias) {
        String validModuleAlias = requireModuleAlias(moduleAlias);
        return validModuleAlias.substring(0, validModuleAlias.indexOf('.'));
    }

    public static String requireIdentifier(String value, String name) {
        if (!isIdentifier(value)) {
            throw new IllegalArgumentException("invalid " + name + ": " + value);
        }
        return value;
    }

    public static String requireCode(String value, String name) {
        requireText(value, name);
        if (!value.matches(CODE_PATTERN)) {
            throw new IllegalArgumentException("invalid " + name + ": " + value);
        }
        return value;
    }

    public static String requireActionCode(String value, String name) {
        requireText(value, name);
        if (!value.matches("[a-z][A-Za-z0-9_]{0,63}")) {
            throw new IllegalArgumentException("invalid " + name + ": " + value);
        }
        return value;
    }

    public static String requireDatabaseName(String value, String name) {
        return requireIdentifier(value, name);
    }

    public static String requireFieldName(String value, String name) {
        if (value == null || !value.matches(FIELD_NAME_PATTERN)) {
            throw new IllegalArgumentException("invalid " + name + ": " + value);
        }
        return value;
    }

    public static boolean isIdentifier(String value) {
        return value != null && value.matches(IDENTIFIER_PATTERN);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new NullPointerException(name + " must not be blank");
        }
    }
}
