package net.ximatai.muyun.spring.platform.reference;

import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceLoadResolver;
import net.ximatai.muyun.spring.ability.reference.ReferenceReadFacade;
import net.ximatai.muyun.spring.ability.reference.ReferenceTo;
import net.ximatai.muyun.spring.ability.reference.ReferencedBy;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.Sort;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformReferencedByResolverTest {
    @Test
    void shouldValidateReferencedBySourceServiceAtStartup() {
        assertThatCode(() -> new PlatformReferencedByResolver(new StaticAbilityCatalog(List.of(
                new ModelAbility(TargetRecord.class), new ModelAbility(SourceRecord.class)))))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectMissingReferencedBySourceServiceAtStartup() {
        assertThatThrownBy(() -> new PlatformReferencedByResolver(new StaticAbilityCatalog(List.of(new ModelAbility(TargetRecord.class)))))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("@ReferencedBy source service is not registered")
                .hasMessageContaining(SourceRecord.class.getName());
    }

    @Test
    void shouldBatchPopulateReferencedByThroughDomainReadFacade() {
        TargetRecord first = target("target-1");
        TargetRecord second = target("target-2");
        SourceRecord firstSource = source("source-1", "target-1");
        SourceRecord secondSource = source("source-2", "target-2");
        SourceAbility sourceAbility = new SourceAbility(List.of(firstSource, secondSource));
        ModelAbility targetAbility = new ModelAbility(TargetRecord.class);
        PlatformReferencedByResolver resolver = new PlatformReferencedByResolver(
                new StaticAbilityCatalog(List.of(targetAbility, sourceAbility)));

        new ReferenceReadFacade(ReferenceLoadResolver.NONE, resolver).enrich(targetAbility, List.of(first, second));

        assertThat(sourceAbility.listCalls).isEqualTo(1);
        assertThat(first.sources).containsExactly(firstSource);
        assertThat(second.sources).containsExactly(secondSource);
    }

    private static TargetRecord target(String id) {
        TargetRecord record = new TargetRecord();
        record.setId(id);
        return record;
    }

    private static SourceRecord source(String id, String targetRecordId) {
        SourceRecord record = new SourceRecord();
        record.setId(id);
        record.targetRecordId = targetRecordId;
        return record;
    }

    private static final class TargetRecord extends StandardEntity {
        @ReferencedBy
        private transient List<SourceRecord> sources;
    }

    private static final class SourceRecord extends StandardEntity {
        @ReferenceTo(target = TargetRecordService.class)
        private String targetRecordId;
    }

    public static final class TargetRecordService {
        public static final String MODULE_ALIAS = "test.targetRecord";
    }

    private static class ModelAbility implements CrudAbility<StandardEntity> {
        private final Class<?> modelClass;

        private ModelAbility(Class<?> modelClass) {
            this.modelClass = modelClass;
        }

        @Override
        public Class<?> modelClass() {
            return modelClass;
        }

        @Override
        public BaseDao<StandardEntity, String> getDao() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getModuleAlias() {
            return "test." + modelClass.getSimpleName();
        }
    }

    private static final class SourceAbility extends ModelAbility {
        private final List<StandardEntity> records;
        private int listCalls;

        private SourceAbility(List<SourceRecord> records) {
            super(SourceRecord.class);
            this.records = List.copyOf(records);
        }

        @Override
        public List<StandardEntity> list(Criteria criteria, Sort... sorts) {
            listCalls++;
            return records;
        }
    }
}
