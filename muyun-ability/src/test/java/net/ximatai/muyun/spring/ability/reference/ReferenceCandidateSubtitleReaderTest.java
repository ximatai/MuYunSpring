package net.ximatai.muyun.spring.ability.reference;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.common.model.standard.StandardTitledEntity;

class ReferenceCandidateSubtitleReaderTest {
    private final ReferenceTarget customer = ReferenceTarget.of("crm", "customer");
    private final ReferenceTarget organization = ReferenceTarget.of("crm", "organization");
    private final FakeAbility customers = new FakeAbility();
    private final FakeAbility organizations = new FakeAbility();
    private final ReferenceTargetResolver resolver = new ReferenceTargetResolver() {
        public Optional<ReferenceAbility<?>> resolve(ReferenceTarget target) {
            return customer.equals(target) ? Optional.of(customers)
                    : organization.equals(target) ? Optional.of(organizations) : Optional.empty();
        }
        public Optional<ReferencePlan> referencePlan(ReferenceTarget target, String field) {
            return customer.equals(target) && "organizationId".equals(field)
                    ? Optional.of(ReferencePlan.of(field, organization, ReferenceCardinality.ONE)) : Optional.empty();
        }
    };

    @Test
    void batchesDeclaredHopsAndOmitsUnavailableRelatedRecords() {
        customers.values = Map.of("one", Map.of("organizationId", "org1"),
                        "two", Map.of("organizationId", "org2"), "hidden", Map.of("organizationId", "denied"));
        organizations.values = Map.of("org1", Map.of("title", " 第一家 "), "org2", Map.of("title", "第二家"));
        assertThat(ReferenceCandidateSubtitleReader.read(customer, List.of("one", "two", "hidden"),
                new ReferenceSelectionProjection("organizationId.title"), resolver))
                .containsExactlyInAnyOrderEntriesOf(Map.of("one", "第一家", "two", "第二家"));
        assertThat(customers.ids).containsExactly("one", "two", "hidden");
        assertThat(customers.fields).containsExactly("organizationId");
        assertThat(organizations.ids).containsExactly("org1", "org2", "denied");
        assertThat(organizations.fields).containsExactly("title");
    }

    @Test
    void excludesProtectedTerminalAndHopBeforeReadingValues() {
        organizations.protectedField = "title";
        assertThat(ReferenceCandidateSubtitleReader.read(customer, List.of("one"),
                new ReferenceSelectionProjection("organizationId.title"), resolver)).isEmpty();
        assertThat(customers.reads).isZero();
        assertThat(organizations.reads).isZero();
        organizations.protectedField = null;
        customers.protectedField = "organizationId";
        assertThat(ReferenceCandidateSubtitleReader.read(customer, List.of("one"),
                new ReferenceSelectionProjection("organizationId.title"), resolver)).isEmpty();
        assertThat(customers.reads).isZero();
    }

    @Test
    void exposesOnlyBoundedNonblankScalars() {
        Map<String, Map<String, Object>> values = new LinkedHashMap<>();
        Map<String, Object> missing = new LinkedHashMap<>();
        missing.put("title", null);
        values.put("null", missing);
        values.put("blank", Map.of("title", "  "));
        values.put("object", Map.of("title", Map.of("private", "secret")));
        values.put("long", Map.of("title", "甲".repeat(600)));
        values.put("number", Map.of("title", 12));
        values.put("boolean", Map.of("title", true));
        List<String> ids = List.copyOf(values.keySet());
        customers.values = values;
        assertThat(ReferenceCandidateSubtitleReader.read(customer, ids,
                new ReferenceSelectionProjection("title"), resolver))
                .containsExactlyInAnyOrderEntriesOf(Map.of("long", "甲".repeat(500), "number", "12", "boolean", "true"));
    }

    @Test
    void rejectsUndeclaredHopsWithoutCrudFallback() {
        assertThatThrownBy(() -> ReferenceCandidateSubtitleReader.read(customer, List.of("one"),
                new ReferenceSelectionProjection("arbitrary.title"), resolver))
                .isInstanceOf(PlatformException.class).hasMessageContaining("not a declared reference");
        assertThat(customers.reads).isZero();
    }
    @Test
    void omitsOptionalContextWhenItsAdapterRejectsAccess() {
        customers.failure = new net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException("denied");
        assertThat(ReferenceCandidateSubtitleReader.read(customer, List.of("one"),
                new ReferenceSelectionProjection("title"), resolver)).isEmpty();
    }

    private static final class FakeAbility implements ReferenceAbility<StandardTitledEntity> {
        private Map<String, Map<String, Object>> values = Map.of();
        private String protectedField;
        private List<String> ids;
        private List<String> fields;
        private int reads;
        private RuntimeException failure;
        @Override public BaseDao<StandardTitledEntity, String> getDao() { throw new AssertionError("CRUD fallback"); }
        @Override public String getModuleAlias() { return "crm.customer"; }
        @Override public boolean isReferenceFieldProtected(String field) { return field.equals(protectedField); }
        @Override public Map<String, Map<String, Object>> projections(java.util.Collection<String> ids,
                                                                     java.util.Collection<String> fields) {
            if (failure != null) throw failure;
            reads++;
            this.ids = List.copyOf(ids);
            this.fields = List.copyOf(fields);
            return values;
        }
    }
}
