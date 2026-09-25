package net.ximatai.muyun.spring.common.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApi31ProjectorTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @SuppressWarnings("unchecked")
    void shouldPublishFinalValueRuleSemanticsWithoutRequiringPatchFields() {
        var rules = new net.ximatai.muyun.spring.common.model.constraint.FieldWriteRules(true, false,
                net.ximatai.muyun.spring.common.model.constraint.TextNormalization.TRIM, true);
        PlatformApiDocument document = new TestDocument() {
            @Override public Map<String, PlatformApiDocument.Schema> schemas() {
                var property = new PlatformApiDocument.Property("string", null, false, true, false,
                        null, null, null, null, null, List.of()).withWriteRules(rules);
                return Map.of("Customer", new PlatformApiDocument.Schema("Customer", "object", null,
                        List.of(), Map.of("code", property), null).withWriteOperation(
                                net.ximatai.muyun.spring.common.model.constraint.WriteOperation.UPDATE, true));
            }
        };
        var components = (Map<String, Object>) OpenApi31Projector.project(document).get("components");
        var schemas = (Map<String, Object>) components.get("schemas");
        var customer = (Map<String, Object>) schemas.get("Customer");
        assertThat(customer).containsEntry("x-muyun-write-operation", "UPDATE")
                .containsEntry("x-muyun-partial-update", true).doesNotContainKey("required");
        var properties = (Map<String, Object>) customer.get("properties");
        var code = (Map<String, Object>) properties.get("code");
        assertThat((Map<String, Object>) code.get("x-muyun-write-rules"))
                .containsExactlyInAnyOrderEntriesOf(Map.of("semantics", "FINAL_VALUE", "requiredOnInsert", true,
                        "requiredOnUpdate", false, "textNormalization", "TRIM", "nonNull", true));
    }

    @Test
    void shouldProjectAnOpenApi31DocumentAcceptedByTheStandardParser() throws Exception {
        PlatformApiDocument document = new TestDocument();

        Map<String, Object> projected = OpenApi31Projector.project(document);
        var parsed = new OpenAPIV3Parser().readContents(objectMapper.writeValueAsString(projected), null,
                new ParseOptions());

        assertThat(parsed.getMessages()).isEmpty();
        assertThat(parsed.getOpenAPI()).isNotNull();
        assertThat(parsed.getOpenAPI().getOpenapi()).isEqualTo(OpenApi31Projector.VERSION);
        assertThat(parsed.getOpenAPI().getPaths()).containsKey("/crm.customer/view/{id}");
        assertThat(parsed.getOpenAPI().getComponents().getSecuritySchemes()).containsKey("bearerAuth");
        assertThat(parsed.getOpenAPI().getPaths().get("/crm.customer/export").getPost().getResponses()
                .get("200").getContent())
                .containsKey("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(projected).containsEntry("x-muyun-module-alias", "crm.customer");
        assertThat(projected).containsEntry("x-muyun-module-base-path", "/crm.customer");
    }

    private static class TestDocument implements PlatformApiDocument {
        @Override
        public String moduleAlias() {
            return "crm.customer";
        }

        @Override
        public String title() {
            return "客户";
        }

        @Override
        public String basePath() {
            return "/crm.customer";
        }

        @Override
        public List<Operation> operations() {
            return List.of(
                    new Operation("GET", "/crm.customer/view/{id}", "crm_customer_view",
                            "View customer", null, "Customer", "view", "crm.customer:view",
                            List.of("RESOURCE_NOT_FOUND")),
                    new Operation("POST", "/crm.customer/export", "crm_customer_export",
                            "Export customers", null, "BinaryFile", "export", "crm.customer:export",
                            List.of(), 200,
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
            );
        }

        @Override
        public Map<String, Schema> schemas() {
            return Map.of(
                    "Customer", new Schema("Customer", "object", null, List.of("name"),
                            Map.of("name", new Property("string", null, true, false, false,
                                    null, null, null, null, null, null, List.of())), null),
                    "BinaryFile", new Schema("BinaryFile", "string", "binary", List.of(), Map.of(), null)
            );
        }

        @Override
        public Map<String, ErrorResponse> errors() {
            return Map.of("RESOURCE_NOT_FOUND", new ErrorResponse("RESOURCE_NOT_FOUND", 404,
                    "PlatformWebError"));
        }
    }
}
