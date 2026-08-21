package net.ximatai.muyun.spring.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ManagedDetailRelationWireWebTest {
    private static final long PRECISE_LONG = 9_007_199_254_740_993L;
    private static final BigDecimal PRECISE_DECIMAL = new BigDecimal("0.123456789012345678");

    @Test
    void shouldApplyCompleteChildWireFactsInsideStandardParentResponse() {
        PreciseParent parent = new PreciseParent();
        parent.setId("parent-1");
        parent.setProperties(List.of(preciseChild("child-1")));

        @SuppressWarnings("unchecked")
        Map<String, Object> wire = (Map<String, Object>) StaticModuleWebWireValues.adapt(parent, Map.of(
                "properties.hiddenLong", FieldValueType.LONG,
                "properties.hiddenDecimal", FieldValueType.DECIMAL), new ObjectMapper());

        assertThat((List<Map<String, Object>>) wire.get("properties")).singleElement().satisfies(child -> {
            assertThat(child.get("hiddenLong")).isEqualTo("9007199254740993");
            assertThat(child.get("hiddenDecimal")).isEqualTo("0.123456789012345678");
        });
    }

    private static PreciseChild preciseChild(String id) {
        PreciseChild child = new PreciseChild();
        child.setId(id);
        child.setVersion(1);
        child.setHiddenLong(PRECISE_LONG);
        child.setHiddenDecimal(PRECISE_DECIMAL);
        return child;
    }

    public static final class PreciseParent extends StandardEntity {
        private List<PreciseChild> properties;

        public List<PreciseChild> getProperties() { return properties; }
        public void setProperties(List<PreciseChild> properties) { this.properties = properties; }
    }

    public static final class PreciseChild extends StandardEntity {
        private Long hiddenLong;
        private BigDecimal hiddenDecimal;

        public Long getHiddenLong() { return hiddenLong; }
        public void setHiddenLong(Long hiddenLong) { this.hiddenLong = hiddenLong; }
        public BigDecimal getHiddenDecimal() { return hiddenDecimal; }
        public void setHiddenDecimal(BigDecimal hiddenDecimal) { this.hiddenDecimal = hiddenDecimal; }
    }
}
