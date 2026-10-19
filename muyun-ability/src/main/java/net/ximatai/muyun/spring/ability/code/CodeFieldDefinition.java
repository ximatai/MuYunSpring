package net.ximatai.muyun.spring.ability.code;

/** Business-object field role and generation timing; numbering strategy belongs to rules. */
public record CodeFieldDefinition(boolean primary, CodeTrigger trigger) {
    public static final int MAX_CODE_LENGTH = 256;
    public CodeFieldDefinition { trigger = trigger == null ? CodeTrigger.SAVE : trigger; }
}
