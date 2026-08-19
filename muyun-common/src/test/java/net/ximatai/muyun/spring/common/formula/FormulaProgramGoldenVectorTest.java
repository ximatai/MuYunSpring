package net.ximatai.muyun.spring.common.formula;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * Locks the wire programs shared with {@code muyun-web}. The JSON fixture is intentionally source controlled
 * instead of generated during a front-end build: Java verifies server compilation, while Vitest executes the
 * same issued JSON without creating a cross-toolchain build dependency.
 */
class FormulaProgramGoldenVectorTest {
    private final FormulaEngine engine = new FormulaEngine();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void compilesTheProgramsConsumedByTheBrowserGoldenVectors() throws IOException {
        JsonNode vectors = fixture().path("vectors");
        assertThat(vectors.isArray()).isTrue();

        for (JsonNode vector : vectors) {
            FormulaProgram program = "WEB_UI".equals(vector.path("profile").asText())
                    ? engine.compileWebUiProgram(vector.path("expression").asText())
                    : engine.compileFormComputeProgram(vector.path("expression").asText());

            JsonNode serialized = objectMapper.valueToTree(program);
            assertThat(serialized)
                    .as("server-issued program for %s", vector.path("id").asText())
                    .isEqualTo(vector.path("program"));

            FormulaProgram roundTripped = objectMapper.treeToValue(vector.path("program"), FormulaProgram.class);
            assertThat(roundTripped)
                    .as("descriptor JSON round trip for %s", vector.path("id").asText())
                    .isEqualTo(program);
        }
    }

    @Test
    void keepsGoldenVectorEvaluationSemanticsOnTheServer() throws IOException {
        for (JsonNode vector : fixture().path("vectors")) {
            Map<String, Object> record = objectMapper.convertValue(vector.path("record"), Map.class);
            JsonNode expected = vector.path("expected");
            if ("WEB_UI".equals(vector.path("profile").asText())) {
                Object actual = engine.evaluateValue(vector.path("expression").asText(), FormulaRuntimeData.of(record));
                assertThat(actual).as(vector.path("id").asText()).isEqualTo(expected.booleanValue());
            } else {
                Map<String, Object> typedRecord = new LinkedHashMap<>(record);
                java.util.List<FormulaFieldDefinition> fieldDefinitions = typedRecord.keySet().stream()
                        .map(field -> FormulaFieldDefinition.of(field, "amount".equals(field)
                                ? FormulaValueType.valueOf(vector.path("targetValueType").asText())
                                : FormulaValueType.ANY))
                        .toList();
                FormulaRuntimeData data = FormulaRuntimeData.typed(typedRecord, Map.of(), fieldDefinitions);
                FormulaExecutionResult result = engine.execute(java.util.List.of(
                        new FormulaRule(vector.path("id").asText(), vector.path("expression").asText())), data);
                assertThat(result.report().hasErrors()).as(vector.path("id").asText()).isFalse();
                Object typedValue = typedRecord.get("amount");
                JsonNode expectedValue = expected.path("amount");
                if (expectedValue.isNumber()) {
                    assertThat(new java.math.BigDecimal(String.valueOf(typedValue)))
                            .as(vector.path("id").asText()).isEqualByComparingTo(expectedValue.decimalValue());
                } else if (expectedValue.isBoolean()) {
                    assertThat(typedValue).as(vector.path("id").asText()).isEqualTo(expectedValue.booleanValue());
                } else {
                    assertThat(typedValue).as(vector.path("id").asText()).isEqualTo(expectedValue.textValue());
                }
            }
        }
    }

    @Test
    void rejectsNonFiniteWebUiLiteralsBeforeTheyBecomeJsonPrograms() {
        assertThatThrownBy(() -> engine.compileWebUiProgram("{value} == 1e309"))
                .isInstanceOf(FormulaEvaluationException.class)
                .hasMessageContaining("WEB_UI profile");
    }

    @Test
    void normalizesNanTextTheSameWayAsTheSharedNonFiniteComputeVector() {
        assertThat(engine.evaluateValue("({source} * 1) + 1", FormulaRuntimeData.of(Map.of("source", "NaN"))))
                .isEqualTo(1d);
    }

    private JsonNode fixture() throws IOException {
        return objectMapper.readTree(Files.readString(findFixture()));
    }

    private Path findFixture() {
        for (Path current = Path.of("").toAbsolutePath(); current != null; current = current.getParent()) {
            Path candidate = current.resolve("contracts/formula-program-golden-vectors.json");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        fail("formula program golden vector fixture is not available from " + Path.of("").toAbsolutePath());
        throw new IllegalStateException("unreachable");
    }
}
