package net.ximatai.muyun.spring.starter.configuration.security;

import net.ximatai.muyun.spring.ability.security.AesGcmFieldCryptoProvider;
import net.ximatai.muyun.spring.ability.security.FieldCryptoProvider;
import net.ximatai.muyun.spring.ability.security.FieldSigner;
import net.ximatai.muyun.spring.ability.security.HmacSha256FieldSigner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MuYunSpringFieldProtectionProperties.class)
@ConditionalOnProperty(prefix = "muyun.security.field-protection", name = "key-base64")
public class MuYunSpringFieldProtectionConfiguration {
    @Bean
    @ConditionalOnMissingBean
    FieldCryptoProvider fieldCryptoProvider(MuYunSpringFieldProtectionProperties properties) {
        return new AesGcmFieldCryptoProvider(decodeKey(properties));
    }

    @Bean
    @ConditionalOnMissingBean
    FieldSigner fieldSigner(MuYunSpringFieldProtectionProperties properties) {
        return new HmacSha256FieldSigner(deriveSigningKey(decodeKey(properties)));
    }

    private static byte[] decodeKey(MuYunSpringFieldProtectionProperties properties) {
        String value = properties == null ? null : properties.getKeyBase64();
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("muyun.security.field-protection.key-base64 must not be blank");
        }
        try {
            return Base64.getDecoder().decode(value.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("muyun.security.field-protection.key-base64 must be valid Base64", exception);
        }
    }

    private static byte[] deriveSigningKey(byte[] encryptionKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("muyun.field-protection.signing.v1".getBytes(StandardCharsets.UTF_8));
            return digest.digest(encryptionKey);
        } catch (Exception exception) {
            throw new IllegalStateException("cannot derive field-signing key", exception);
        }
    }
}
