package net.ximatai.muyun.spring.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebQueryCriteriaWireContractTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldDeserializeOrderedRecursiveCriteriaNodesByKind() throws Exception {
        WebQueryRequest request = objectMapper.readValue("""
                {
                  "criteria": {
                    "kind": "GROUP",
                    "operator": "AND",
                    "children": [
                      {"kind": "CONDITION", "fieldName": "status", "operator": "EQ", "values": ["active"]},
                      {"kind": "GROUP", "operator": "OR", "children": [
                        {"kind": "CONDITION", "fieldName": "ownerId", "operator": "EQ", "values": ["u-1"]},
                        {"kind": "CONDITION", "fieldName": "ownerId", "operator": "EQ", "values": ["u-2"]}
                      ]}
                    ]
                  }
                }
                """, WebQueryRequest.class);

        assertThat(request.criteria().kind()).isEqualTo(WebQueryCriteriaNodeKind.GROUP);
        assertThat(request.criteria().children())
                .hasSize(2)
                .allSatisfy(node -> assertThat(node.kind()).isNotNull());
        assertThat(request.criteria().children().get(0)).isInstanceOf(WebQueryCondition.class);
        assertThat(request.criteria().children().get(1)).isInstanceOf(WebQueryCriteria.class);
    }

    @Test
    void shouldRejectUnknownCriteriaNodeKindButKeepTopLevelConditionsCompatible() throws Exception {
        assertThatThrownBy(() -> objectMapper.readValue("""
                {"criteria":{"kind":"UNSUPPORTED","operator":"AND","children":[]}}
                """, WebQueryRequest.class))
                .hasMessageContaining("UNSUPPORTED");
        assertThatThrownBy(() -> objectMapper.readValue("""
                {"criteria":{"kind":"GROUP","operator":"AND","children":[{"fieldName":"status","values":["active"]}]}}
                """, WebQueryRequest.class))
                .hasMessageContaining("missing type id property 'kind'");

        WebQueryRequest request = objectMapper.readValue("""
                {"conditions":[{"fieldName":"status","operator":"EQ","values":["active"]}]}
                """, WebQueryRequest.class);

        assertThat(request.conditions()).containsExactly(
                new WebQueryCondition("status", "EQ", List.of("active")));
    }
}
