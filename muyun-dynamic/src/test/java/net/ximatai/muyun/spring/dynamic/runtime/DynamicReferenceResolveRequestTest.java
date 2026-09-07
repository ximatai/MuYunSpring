package net.ximatai.muyun.spring.dynamic.runtime;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamicReferenceResolveRequestTest {
    @Test
    void shouldPreserveExplicitNullFormValuesInAnImmutableSnapshot() {
        var form = new LinkedHashMap<String, Object>();
        form.put("region", null);
        form.put("title", "采购单");
        var request = DynamicReferenceResolveRequest.query(null).withFormValues(form);
        form.put("title", "later change");

        assertThat(request.formValues()).containsEntry("region", null).containsEntry("title", "采购单");
        assertThat(request.withoutProjections().formValues()).containsEntry("region", null);
        assertThatThrownBy(() -> request.formValues().put("region", "north"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
