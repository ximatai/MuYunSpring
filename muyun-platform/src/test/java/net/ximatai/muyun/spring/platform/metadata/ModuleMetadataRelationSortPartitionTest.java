package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.ability.BaseDao;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModuleMetadataRelationSortPartitionTest {
    @SuppressWarnings("unchecked")
    private final ModuleMetadataRelationService service = new ModuleMetadataRelationService(
            mock(BaseDao.class), mock(net.ximatai.muyun.spring.platform.module.PlatformModuleService.class),
            mock(MetadataService.class));

    @Test
    void shouldOnlyPermitRelationSortingAmongSiblingsOfTheSameParent() {
        ModuleMetadataRelation left = relation("metadata-parent-a");
        ModuleMetadataRelation sibling = relation("metadata-parent-a");
        ModuleMetadataRelation anotherBranch = relation("metadata-parent-b");

        assertThatCode(() -> service.sortPartition().requireSamePartition(left, sibling)).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.sortPartition().requireSamePartition(left, anotherBranch))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("same parent metadata");
    }

    @Test
    void shouldAllowMainRelationWithoutPuttingNullIntoItsCriteria() {
        ModuleMetadataRelation main = relation(null);
        main.setRelationRole(RelationRole.MAIN);

        assertThatCode(() -> service.sortPartition().criteriaFor(main)).doesNotThrowAnyException();
    }

    private ModuleMetadataRelation relation(String parentMetadataId) {
        ModuleMetadataRelation relation = new ModuleMetadataRelation();
        relation.setModuleAlias("education.exam");
        relation.setParentMetadataId(parentMetadataId);
        return relation;
    }
}
