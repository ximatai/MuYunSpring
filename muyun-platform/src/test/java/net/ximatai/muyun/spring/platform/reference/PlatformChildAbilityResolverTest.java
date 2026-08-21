package net.ximatai.muyun.spring.platform.reference;

import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.DataScopeAbility;
import net.ximatai.muyun.spring.ability.child.ChildAbility;
import net.ximatai.muyun.spring.ability.child.ChildAbilityRequest;
import net.ximatai.muyun.spring.ability.child.ChildOf;
import net.ximatai.muyun.spring.ability.child.Children;
import net.ximatai.muyun.spring.ability.child.ChildrenAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceTo;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformChildAbilityResolverTest {
    @Test
    void shouldResolveDeclaredStaticChildAbilityAtStartup() {
        ChildRecordAbility child = new ChildRecordAbility();
        PlatformChildAbilityResolver resolver = new PlatformChildAbilityResolver(
                new StaticAbilityCatalog(List.of(new ParentRecordAbility(), child)));

        assertThat(resolver.resolve(ChildAbilityRequest.forStaticModel(ChildRecord.class))).containsSame(child);
    }

    @Test
    void shouldRejectMissingDeclaredChildAbilityAtStartup() {
        assertThatThrownBy(() -> new PlatformChildAbilityResolver(
                new StaticAbilityCatalog(List.of(new ParentRecordAbility()))))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("@Children child service is not registered")
                .hasMessageContaining(ChildRecord.class.getName());
    }

    @Test
    void shouldRejectAutomaticChildrenWithIndependentlyDataScopedChildAtStartup() {
        assertThatThrownBy(() -> new PlatformChildAbilityResolver(
                new StaticAbilityCatalog(List.of(new ParentRecordAbility(), new DataScopedChildRecordAbility()))))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("automatic @Children aggregate reads do not support independent DataScopeAbility")
                .hasMessageContaining("children");
    }

    @Test
    void shouldAllowManualChildRelationWithoutCatalogedChildAbility() {
        assertThatCode(() -> new PlatformChildAbilityResolver(
                new StaticAbilityCatalog(List.of(new ManualParentRecordAbility()))))
                .doesNotThrowAnyException();
    }

    private static final class ParentRecord extends StandardEntity {
        @Children
        private List<ChildRecord> children;
    }

    private static final class ChildRecord extends StandardEntity {
        @ChildOf
        @ReferenceTo(target = ParentRecordService.class)
        private String parentRecordId;
    }

    public static final class ParentRecordService {
        public static final String MODULE_ALIAS = "test.parentRecord";
    }

    private record ParentRecordAbility() implements CrudAbility<StandardEntity>, ChildrenAbility<StandardEntity> {
        @Override
        public BaseDao<StandardEntity, String> getDao() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getModuleAlias() {
            return "test.parentRecord";
        }

        @Override
        public Class<?> modelClass() {
            return ParentRecord.class;
        }
    }

    private record ChildRecordAbility() implements CrudAbility<StandardEntity>, ChildAbility<StandardEntity> {
        @Override
        public BaseDao<StandardEntity, String> getDao() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getModuleAlias() {
            return "test.childRecord";
        }

        @Override
        public Class<?> modelClass() {
            return ChildRecord.class;
        }
    }

    private record DataScopedChildRecordAbility()
            implements CrudAbility<StandardEntity>, ChildAbility<StandardEntity>, DataScopeAbility<StandardEntity> {
        @Override
        public BaseDao<StandardEntity, String> getDao() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getModuleAlias() {
            return "test.childRecord";
        }

        @Override
        public Class<?> modelClass() {
            return ChildRecord.class;
        }

        @Override
        public DataScopeCriteriaService getDataScopeCriteriaService() {
            throw new UnsupportedOperationException();
        }
    }

    private record ManualParentRecordAbility() implements CrudAbility<StandardEntity>, ChildrenAbility<StandardEntity> {
        @Override
        public BaseDao<StandardEntity, String> getDao() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getModuleAlias() {
            return "test.parentRecord";
        }

        @Override
        public Class<?> modelClass() {
            return ParentRecord.class;
        }

        @Override
        public boolean usesAutomaticChildRelations() {
            return false;
        }
    }
}
