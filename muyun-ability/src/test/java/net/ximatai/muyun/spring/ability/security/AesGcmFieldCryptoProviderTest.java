package net.ximatai.muyun.spring.ability.security;

import net.ximatai.muyun.spring.common.security.FieldProtectionException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmFieldCryptoProviderTest {
    private final AesGcmFieldCryptoProvider crypto = new AesGcmFieldCryptoProvider(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    @Test
    void encryptsWithRandomIvAndBindsCiphertextToFieldName() {
        String first = crypto.encrypt("apiKey", "secret-value");
        String second = crypto.encrypt("apiKey", "secret-value");

        assertThat(first).startsWith("v1:").isNotEqualTo(second);
        assertThat(crypto.decrypt("apiKey", first)).isEqualTo("secret-value");
        assertThatThrownBy(() -> crypto.decrypt("anotherField", first))
                .isInstanceOf(FieldProtectionException.class);
    }

    @Test
    void signerDetectsTamperedPlainValue() {
        HmacSha256FieldSigner signer = new HmacSha256FieldSigner(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        String signature = signer.sign("apiKey", "secret-value");

        signer.verify("apiKey", "secret-value", signature);
        assertThatThrownBy(() -> signer.verify("apiKey", "changed", signature))
                .isInstanceOf(FieldProtectionException.class)
                .hasMessageContaining("field signature mismatch: apiKey");
    }
}
