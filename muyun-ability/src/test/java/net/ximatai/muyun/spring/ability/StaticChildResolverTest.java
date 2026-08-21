package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.ability.child.ChildPlan;
import net.ximatai.muyun.spring.ability.child.ChildOf;
import net.ximatai.muyun.spring.ability.child.Children;
import net.ximatai.muyun.spring.ability.reference.ReferenceTo;
import net.ximatai.muyun.spring.ability.reference.ReferenceIntegrity;
import net.ximatai.muyun.spring.ability.reference.ReferenceTargetUnavailablePolicy;
import net.ximatai.muyun.spring.ability.child.StaticChildResolver;
import net.ximatai.muyun.spring.ability.child.StaticChildResolverTestAccess;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StaticChildResolverTest {
    @BeforeEach
    void setUp() {
        StaticChildResolverTestAccess.clearCacheForTests();
    }

    @Test
    void plansShouldCompileChildrenFromChildOwnership() {
        assertThat(StaticChildResolver.plans(DemoInvoice.class))
                .containsExactly(
                        new ChildPlan("lines", "demoInvoice", "demoInvoiceLine", "invoiceId", true, true),
                        new ChildPlan("notes", "demoInvoice", "demoInvoiceNote", "invoiceId", true, true)
                );
        assertThat(StaticChildResolver.plan(DemoInvoice.class, "notes"))
                .isEqualTo(new ChildPlan("notes", "demoInvoice", "demoInvoiceNote", "invoiceId", true, true));
    }

    @Test
    void plansShouldCompileAggregateOwnershipFromChildForeignKey() {
        assertThat(StaticChildResolver.plans(OwnedParent.class))
                .containsExactly(new ChildPlan("items", "ownedParent", "ownedItem", "parentId", true, true));
    }

    @Test
    void aggregateOwnershipShouldDelegateParentDeletionToReferenceIntegrity() {
        assertThat(StaticChildResolver.plans(PreservedOwnedParent.class))
                .containsExactly(new ChildPlan("items", "preservedOwnedParent", "preservedOwnedItem", "parentId", true, false));
        assertThat(StaticChildResolver.plans(RestrictedOwnedParent.class))
                .containsExactly(new ChildPlan("items", "restrictedOwnedParent", "restrictedOwnedItem", "parentId", true, false));
    }

    @Test
    void ruleShouldReadPopulateAndWriteDeclaredChildFields() {
        StaticChildResolver.ChildRule rule = StaticChildResolver.rule(DemoInvoice.class, "lines");
        DemoInvoice invoice = new DemoInvoice("Invoice", List.of());
        DemoInvoiceLine line = new DemoInvoiceLine("First line");

        rule.setParentId(line, "invoice-1");
        rule.populate(invoice, List.of(line));

        assertThat(line.getInvoiceId()).isEqualTo("invoice-1");
        assertThat(rule.<DemoInvoice, DemoInvoiceLine>children(invoice))
                .containsExactly(line);
    }

    @Test
    void singlePlanShouldRejectMultipleChildRelations() {
        assertThatThrownBy(() -> StaticChildResolver.singlePlan(DemoInvoice.class))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("expected exactly one child relation plan")
                .hasMessageContaining("lines")
                .hasMessageContaining("notes")
                .hasMessageContaining("childRelation(relationCode");
    }

    @Test
    void singlePlanShouldRejectMissingChildRelations() {
        assertThatThrownBy(() -> StaticChildResolver.singlePlan(NoChildRecord.class))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("expected exactly one child relation plan")
                .hasMessageContaining("actual relationCodes: []")
                .hasMessageContaining("@Children/@ChildOf");
    }

    @Test
    void singlePlanShouldRejectMissingParentModelClass() {
        assertThatThrownBy(() -> StaticChildResolver.singlePlan(null))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("parentModelClass");
    }

    @Test
    void planShouldRejectUnknownRelationCode() {
        assertThatThrownBy(() -> StaticChildResolver.plan(DemoInvoice.class, "missing"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("unknown child relationCode");
    }

    @Test
    void planShouldRejectMissingParentModelClass() {
        assertThatThrownBy(() -> StaticChildResolver.plan(null, "lines"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("parentModelClass");
    }

    @Test
    void childrenShouldRejectAChildOwnershipReferenceToAnotherParent() {
        assertThatThrownBy(() -> StaticChildResolver.plans(MismatchedParent.class))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("exactly one @ChildOf @ReferenceTo foreign key")
                .hasMessageContaining(MismatchedParent.class.getName());
    }

    private static final class NoChildRecord extends StandardEntity {
    }

    private static final class MismatchedParent extends StandardEntity {
        @Children
        private List<MismatchedItem> items;
    }

    private static final class MismatchedItem extends StandardEntity {
        @ChildOf
        @ReferenceTo(target = OwnedParentService.class)
        private String parentId;
    }

    private static final class OwnedParent extends StandardEntity {
        @Children
        private List<OwnedItem> items;
    }

    private static final class OwnedItem extends StandardEntity {
        @ChildOf
        @ReferenceTo(target = OwnedParentService.class,
                integrity = @ReferenceIntegrity(onTargetUnavailable = ReferenceTargetUnavailablePolicy.CASCADE_DELETE))
        private String parentId;
    }

    public static final class OwnedParentService {
        public static final String MODULE_ALIAS = "demo.ownedParent";
    }

    private static final class PreservedOwnedParent extends StandardEntity {
        @Children
        private List<PreservedOwnedItem> items;
    }

    private static final class PreservedOwnedItem extends StandardEntity {
        @ChildOf
        @ReferenceTo(target = PreservedOwnedParentService.class)
        private String parentId;
    }

    public static final class PreservedOwnedParentService {
        public static final String MODULE_ALIAS = "demo.preservedOwnedParent";
    }

    private static final class RestrictedOwnedParent extends StandardEntity {
        @Children
        private List<RestrictedOwnedItem> items;
    }

    private static final class RestrictedOwnedItem extends StandardEntity {
        @ChildOf
        @ReferenceTo(target = RestrictedOwnedParentService.class,
                integrity = @ReferenceIntegrity(onTargetUnavailable = ReferenceTargetUnavailablePolicy.RESTRICT))
        private String parentId;
    }

    public static final class RestrictedOwnedParentService {
        public static final String MODULE_ALIAS = "demo.restrictedOwnedParent";
    }
}
