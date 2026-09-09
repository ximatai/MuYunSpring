package net.ximatai.muyun.spring.platform.permission;

import net.ximatai.muyun.spring.ability.*;
import net.ximatai.muyun.spring.ability.permission.*;
import net.ximatai.muyun.spring.ability.reference.*;
import net.ximatai.muyun.spring.ability.event.*;
import net.ximatai.muyun.spring.common.model.standard.StandardDataScopedEntity;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.common.model.capability.TitledCapable;
import net.ximatai.muyun.spring.common.platform.*;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.database.core.orm.*;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class RecordPermissionServiceTest {
    static class Record extends StandardDataScopedEntity { }
    private final ActionExecutionPolicyService auth = mock(ActionExecutionPolicyService.class);
    private final RuntimeEventPublisher events = mock(RuntimeEventPublisher.class);
    private final RecordPermissionService manager = new RecordPermissionService(auth, events);
    @SuppressWarnings("unchecked") private final DataScopeAbility<Record> service = mock(DataScopeAbility.class);
    private final ReferenceAbility<?> users = mock(ReferenceAbility.class);
    private Record record;
    @BeforeEach void setup() {
        record = new Record(); record.setId("record"); record.setVersion(1); record.setAuthUserId("owner");
        record.setAuthMemberIds("old");
        when(service.dataScopeFieldMapping()).thenReturn(DataScopeFieldMapping.STANDARD);
        when(service.getModuleAlias()).thenReturn("test.order");
        when(service.readScope(eq(PlatformAction.MANAGE_PERMISSIONS), any(Criteria.class)))
                .thenAnswer(invocation -> DataScopeCriteriaResult.unrestricted(invocation.getArgument(1)));
        when(service.selectForAction(PlatformAction.MANAGE_PERMISSIONS, "record")).thenReturn(record);
        when(service.update(any())).thenReturn(1);
        PlatformAbilityRuntime.configureReferenceTargetResolver(target -> Optional.of(users));
    }
    @AfterEach void cleanup() { PlatformAbilityRuntime.resetReferenceTargetResolver(); }
    private RecordPermissionChange add() { return new RecordPermissionChange(1, RecordPermissionChange.Operation.ADD,
            RecordPermissionChange.Relation.MEMBER, List.of("next"), null, null); }
    @Test void independentActionScopePrecedesEveryReadAndWrite() {
        doThrow(new IllegalArgumentException("denied")).when(auth).requireRecordAction(any());
        assertThrows(IllegalArgumentException.class, () -> manager.read(service, "record"));
        assertThrows(IllegalArgumentException.class, () -> manager.change(service, "record", add()));
        verify(service, never()).select(any()); verify(service, never()).update(any()); verifyNoInteractions(events);
    }
    @Test void readsStaticRecordsThroughManagePermissionsScopeWithoutAViewRead() {
        assertEquals("owner", manager.read(service, "record").ownerId());

        verify(service).selectForAction(PlatformAction.MANAGE_PERMISSIONS, "record");
        verify(service, never()).select("record");
        verify(service, never()).count(any());
    }
    @Test void limitsCandidatesToTheirOwnReferenceScopeWhileLoadingCrossTenantPermissionTitles() {
        when(service.readScope(eq(PlatformAction.MANAGE_PERMISSIONS), any(Criteria.class)))
                .thenAnswer(invocation -> DataScopeCriteriaResult.crossTenantUnrestricted(invocation.getArgument(1)));
        record.setTenantId("tenant-b");
        @SuppressWarnings("unchecked")
        BaseDao<ReferenceUser, String> userDao = mock(BaseDao.class);
        AtomicReference<Boolean> titleReadBypassed = new AtomicReference<>(false);
        AtomicReference<Boolean> candidateReadBypassed = new AtomicReference<>(false);
        ReferenceUser titleUser = new ReferenceUser("owner", "Owner");
        ReferenceUser candidateUser = new ReferenceUser("next", "Next");
        when(userDao.query(any(Criteria.class), any(PageRequest.class), any(Sort[].class))).thenAnswer(invocation -> {
            titleReadBypassed.set(TenantContext.tenantFilterBypassed());
            return TenantContext.tenantFilterBypassed() ? List.of(titleUser) : List.of();
        });
        when(userDao.pageQuery(any(Criteria.class), any(PageRequest.class), any(Sort[].class)))
                .thenAnswer(invocation -> {
                    candidateReadBypassed.set(TenantContext.tenantFilterBypassed());
                    PageRequest request = invocation.getArgument(1);
                    List<ReferenceUser> records = TenantContext.tenantFilterBypassed() ? List.of(candidateUser) : List.of();
                    return PageResult.of(records, records.size(), request);
                });

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            PlatformAbilityRuntime.configureReferenceTargetResolver(target -> Optional.of(
                    new ScopedReferenceUserService(userDao, false)));
            assertEquals("Owner", manager.read(service, "record").titles().get("owner"));
            assertTrue(titleReadBypassed.get());
            assertEquals(List.of(), manager.candidates(service, "record", null));
            assertFalse(candidateReadBypassed.get());
            assertThrows(RuntimeException.class, () -> manager.change(service, "record", add()));

            PlatformAbilityRuntime.configureReferenceTargetResolver(target -> Optional.of(
                    new ScopedReferenceUserService(userDao, true)));
            assertEquals(List.of(new ReferenceOption("next", "Next")), manager.candidates(service, "record", null));
            assertTrue(candidateReadBypassed.get());
            manager.change(service, "record", add());
        }
    }
    @Test void rejectsUnavailableRecipientWithoutMutation() {
        when(users.referenceOptions(any(Criteria.class), any(PageRequest.class))).thenReturn(PageResult.of(List.of(), 0, new PageRequest(0, 1)));
        assertThrows(RuntimeException.class, () -> manager.change(service, "record", add()));
        assertEquals("old", record.getAuthMemberIds()); verify(service, never()).update(any());
    }
    @Test void writesUnionAndPublishesAuditOnlyAfterSuccessfulPersistence() {
        when(users.referenceOptions(any(Criteria.class), any(PageRequest.class))).thenReturn(PageResult.of(List.of(new ReferenceOption("next", "Next")), 1, new PageRequest(0, 1)));
        manager.change(service, "record", add());
        assertEquals("old,next", record.getAuthMemberIds());
        verify(auth).requireRecordAction(argThat(context -> context.platformAction() == PlatformAction.MANAGE_PERMISSIONS));
        verify(service).update(record);
        verify(events).publishAfterCommit(argThat(event -> event.actionCode().equals("managePermissions")
                && ((Map<?,?>) event.payload().get("before")).get("memberIds").equals(List.of("old"))));
    }
    @Test void rejectsRecordOutsideScope() {
        when(service.selectForAction(PlatformAction.MANAGE_PERMISSIONS, "record")).thenReturn(null);
        assertThrows(RuntimeException.class, () -> manager.read(service, "record")); verify(service, never()).select(any());
    }
    @Test void noAuditOnFailedWrite() {
        when(users.referenceOptions(any(Criteria.class), any(PageRequest.class))).thenReturn(PageResult.of(List.of(new ReferenceOption("next", "Next")), 1, new PageRequest(0, 1)));
        when(service.update(any())).thenReturn(0);
        assertThrows(OptimisticLockException.class, () -> manager.change(service, "record", add())); verifyNoInteractions(events);
    }

    private static final class ReferenceUser extends StandardEntity implements TitledCapable {
        private final String title;

        private ReferenceUser(String id, String title) {
            setId(id);
            this.title = title;
        }

        @Override
        public String getTitle() {
            return title;
        }
    }

    private static final class ScopedReferenceUserService extends AbstractAbilityService<ReferenceUser>
            implements DataScopeAbility<ReferenceUser>, ReferenceAbility<ReferenceUser> {
        private final boolean crossTenant;

        private ScopedReferenceUserService(BaseDao<ReferenceUser, String> dao, boolean crossTenant) {
            super("iam.user", ReferenceUser.class, dao);
            this.crossTenant = crossTenant;
        }

        @Override
        public DataScopeCriteriaService getDataScopeCriteriaService() {
            return new DataScopeCriteriaService() {
                @Override
                public DataScopeCriteriaResult resolveReadScope(String moduleAlias,
                                                                String actionCode,
                                                                Criteria criteria,
                                                                Optional<CurrentUser> currentUser) {
                    return crossTenant
                            ? DataScopeCriteriaResult.crossTenantUnrestricted(criteria)
                            : DataScopeCriteriaResult.unrestricted(criteria);
                }

                @Override
                public DataScopeCriteriaResult resolveReadScope(String moduleAlias,
                                                                ActionExecutionPolicy policy,
                                                                Criteria criteria,
                                                                Optional<CurrentUser> currentUser) {
                    return resolveReadScope(moduleAlias, policy.actionCode(), criteria, currentUser);
                }
            };
        }
    }
}
