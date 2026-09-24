package net.ximatai.muyun.spring.ability;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.common.platform.*;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.ability.child.*;
import net.ximatai.muyun.spring.ability.reference.ReferenceTo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecordFieldMutationContractTest {
    private static final ActionExecutionPolicy POLICY = new ActionExecutionPolicy("changeSecret",
            PlatformActionLevel.RECORD, ActionAccessMode.AUTH_REQUIRED, true, true,
            ActionDefaultGrantPolicy.NONE, null);

    @AfterEach
    void reset() {
        PlatformAbilityRuntime.resetDataScopeCriteriaService();
        TenantContext.clear();
        CurrentUserContext.clear();
        CacheRegistry.clearAll();
        PlatformAbilityRuntime.resetChildAbilityResolver();
    }

    @Test
    void commandMustKeepActorVersionHooksAndCacheWhileRestrictingWrittenFields() {
        PlatformAbilityRuntime.configureDataScopeCriteriaService(AllowAllDataScopeCriteriaService::new);
        CommandService service = new CommandService();
        try (var tenant = TenantContext.use("tenant-a");
             var actor = CurrentUserContext.use(CurrentUser.tenantUser("operator", "Operator", "tenant-a"))) {
            CommandRecord record = new CommandRecord();
            record.setTitle("Original"); record.setSecret("old");
            String id = service.insert(record);
            assertThat(service.select(id).getSecret()).isEqualTo("old");
            int version = record.getVersion();
            assertThat(service.command(id, changed -> {
                changed.setSecret("new");
                changed.setTitle("Unrequested");
                changed.setTenantId("foreign");
            })).isEqualTo(1);
            CommandRecord saved = service.select(id);
            assertThat(saved.getSecret()).isEqualTo("new");
            assertThat(saved.getTitle()).isEqualTo("Original");
            assertThat(saved.getTenantId()).isEqualTo("tenant-a");
            assertThat(saved.getVersion()).isEqualTo(version + 1);
            assertThat(saved.getUpdatedBy()).isEqualTo("operator");
            assertThat(service.hooks).isEqualTo(1);
            assertThat(ActionExecutionContextHolder.current()).isEmpty();
            saved.setSecret("ordinary overwrite");
            service.update(saved);
            assertThat(service.select(id).getSecret()).isEqualTo("new");
        }
    }

    @Test
    void denialMustHappenBeforeDomainMutationAndStandardFieldsMustBeRejected() {
        CommandService service = new CommandService();
        CommandRecord record = new CommandRecord();
        record.setSecret("old");
        String id = service.insert(record);
        PlatformAbilityRuntime.configureDataScopeCriteriaService(() -> new AllowAllDataScopeCriteriaService() {
            @Override
            public DataScopeCriteriaResult resolveReadScope(String module, ActionExecutionPolicy policy,
                                                            Criteria criteria, Optional<CurrentUser> user) {
                return DataScopeCriteriaResult.restricted(criteria.eq("id", "unavailable"));
            }
        });
        assertThatThrownBy(() -> service.command(id, value -> { throw new AssertionError("must not execute"); }))
                .hasMessageContaining("record data permission denied");
        PlatformAbilityRuntime.configureDataScopeCriteriaService(AllowAllDataScopeCriteriaService::new);
        assertThatThrownBy(() -> service.systemFieldCommand(id)).isInstanceOf(IllegalArgumentException.class);
        assertThat(ActionExecutionContextHolder.current()).isEmpty();
    }

    @Test
    void failingBusinessHookMustNotLeakCommandFieldPermission() {
        PlatformAbilityRuntime.configureDataScopeCriteriaService(AllowAllDataScopeCriteriaService::new);
        CommandService service = new CommandService();
        CommandRecord record = new CommandRecord();
        record.setSecret("old");
        String id = service.insert(record);
        service.reject = true;
        assertThatThrownBy(() -> service.command(id, value -> value.setSecret("failed")))
                .hasMessage("business rejection");
        service.reject = false;
        CommandRecord ordinary = service.select(id);
        ordinary.setSecret("ordinary");
        service.update(ordinary);
        assertThat(service.select(id).getSecret()).isEqualTo("old");
    }

    @Test
    void scalarCommandMustLeaveInitializedAggregateChildrenUntouched() {
        ItemService items = new ItemService();
        AggregateService service = new AggregateService();
        PlatformAbilityRuntime.configureChildAbilityResolver(request -> Optional.of(items));
        AggregateRecord record = new AggregateRecord();
        Item item = new Item();
        record.getItems().add(item);
        service.insert(record);

        service.command(record.getId(), draft -> {
            draft.setState("new");
            draft.setItems(List.of(new Item()));
        }, "state");

        assertThat(service.select(record.getId()).getState()).isEqualTo("new");
        assertThat(items.list(Criteria.of())).extracting(Item::getId).containsExactly(item.getId());
        assertThat(items.select(item.getId()).getVersion()).isEqualTo(0);
    }

    @Test
    void commandMustIsolateNestedValuesAndOnlyPersistDeclaredFields() {
        AggregateService service = new AggregateService();
        AggregateRecord record = new AggregateRecord();
        service.insert(record);
        service.command(record.getId(), draft -> {
            draft.setState("new");
            draft.getAttributes().get("tags").add("unexpected");
            draft.getPayload().bytes[0] = 9;
        }, "state");
        assertThat(service.selectActiveRaw(record.getId()).getAttributes().get("tags")).containsExactly("original");
        assertThat(service.selectActiveRaw(record.getId()).getPayload().bytes).containsExactly((byte) 1);
        assertThat(record.getAttributes().get("tags")).containsExactly("original");

        service.command(record.getId(), draft -> draft.getAttributes().get("tags").add("declared"), "attributes");
        assertThat(service.selectActiveRaw(record.getId()).getAttributes().get("tags"))
                .containsExactly("original", "declared");
        assertThatThrownBy(() -> service.command(record.getId(), draft -> {
            draft.getAttributes().clear();
            throw new IllegalArgumentException("rejected");
        }, "attributes")).hasMessage("rejected");
        assertThat(service.selectActiveRaw(record.getId()).getAttributes().get("tags"))
                .containsExactly("original", "declared");
    }

    @Getter @Setter
    static class AggregateRecord extends StandardEntity {
        private String state = "old";
        private Map<String, List<String>> attributes = new LinkedHashMap<>(Map.of("tags", new ArrayList<>(List.of("original"))));
        private Payload payload = new Payload();
        @Children private List<Item> items = new ArrayList<>();
    }

    static class Payload { byte[] bytes = {1}; }

    @Getter @Setter
    static class Item extends StandardEntity {
        @ChildOf @ReferenceTo(target = AggregateService.class) private String parentId;
    }

    static class ItemService extends AbstractAbilityService<Item> implements ChildAbility<Item>, SoftDeleteAbility<Item> {
        ItemService() { super("test.item", Item.class, new InMemoryBaseDao<>()); }
    }

    public static class AggregateService extends AbstractAbilityService<AggregateRecord> implements ChildrenAbility<AggregateRecord> {
        public static final String MODULE_ALIAS = "test.aggregate_record";
        AggregateService() { super(MODULE_ALIAS, AggregateRecord.class, new InMemoryBaseDao<>()); }
        int command(String id, Consumer<AggregateRecord> mutation, String... fields) {
            return mutateFields(POLICY, id, mutation, fields);
        }
    }

    @Getter @Setter
    static class CommandRecord extends StandardEntity {
        private String title;
        private String secret;
    }

    static class CommandService extends AbstractAbilityService<CommandRecord>
            implements DataScopeAbility<CommandRecord>, CacheAbility<CommandRecord> {
        int hooks;
        boolean reject;
        CommandService() { super("test.command", CommandRecord.class, new InMemoryBaseDao<>()); }
        int command(String id, Consumer<CommandRecord> mutation) { return mutateFields(POLICY, id, mutation, "secret"); }
        int systemFieldCommand(String id) { return mutateFields(POLICY, id, value -> {}, "tenantId"); }
        @Override public void beforeUpdate(CommandRecord record, CommandRecord existing) {
            if (reject) throw new IllegalArgumentException("business rejection");
            retainCommandFields(record, existing == null ? selectActiveRaw(record.getId()) : existing, "secret");
        }
        @Override public void afterUpdate(CommandRecord record, int count) { hooks++; }
    }
}
