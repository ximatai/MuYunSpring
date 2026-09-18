package net.ximatai.muyun.spring.starter.configuration.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Master key material for platform fields marked as encrypted and signed. */
@ConfigurationProperties("muyun.security.field-protection")
public class MuYunSpringFieldProtectionProperties {
    /** Base64-encoded AES key; configure through a deployment secret, never application source. */
    private String keyBase64;

    public String getKeyBase64() {
        return keyBase64;
    }

    public void setKeyBase64(String keyBase64) {
        this.keyBase64 = keyBase64;
    }
}
