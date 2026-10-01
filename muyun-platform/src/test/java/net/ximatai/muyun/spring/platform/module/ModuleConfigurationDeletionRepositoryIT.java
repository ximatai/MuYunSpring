package net.ximatai.muyun.spring.platform.module;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.spring.boot.sql.annotation.EnableMuYunRepositories;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelation;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelationDao;
import net.ximatai.muyun.spring.platform.metadata.RelationRole;
import net.ximatai.muyun.spring.platform.reference.StaticReferenceDeletionGuard;
import net.ximatai.muyun.spring.platform.support.PlatformPostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = ModuleConfigurationDeletionRepositoryIT.TestApplication.class)
class ModuleConfigurationDeletionRepositoryIT extends PlatformPostgresIntegrationTest {
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("muyun.database.repository-schema-mode", () -> "ENSURE");
    }

    @Autowired PlatformModuleService modules;
    @Autowired RelationSource relations;

    @AfterEach
    void resetRuntime() {
        PlatformAbilityRuntime.resetReferenceDeletionGuard();
    }

    @ParameterizedTest
    @CsvSource({"MAIN,false", "CHILD,false", "MAIN,true", "CHILD,true"})
    void requiresRemovingPersistedModelBindingsBeforeModuleDeletion(RelationRole role, boolean hardDelete) {
        PlatformAbilityRuntime.configureReferenceDeletionGuard(new StaticReferenceDeletionGuard(List.of(relations)));
        PlatformModule module = new PlatformModule();
        module.setAlias("cleanup." + role.name().toLowerCase() + (hardDelete ? "_hard" : "_soft"));
        module.setApplicationAlias("cleanup");
        module.setTitle("删除依赖契约");
        modules.insert(module);
        ModuleMetadataRelation relation = new ModuleMetadataRelation();
        relation.setModuleAlias(module.getAlias());
        relation.setMetadataId("retained_metadata");
        relation.setRelationAlias("model");
        relation.setRelationRole(role);
        relation.setTitle("尚未清理的实体绑定");
        String relationId = relations.insert(relation);

        assertThatThrownBy(() -> remove(module, hardDelete))
                .isInstanceOf(PlatformException.class)
                .satisfies(error -> {
                    PlatformException failure = (PlatformException) error;
                    assertThat(failure.code()).isEqualTo("RESOURCE_IN_USE");
                    assertThat(failure.details()).containsEntry("sourceModuleAlias", "platform.module_metadata_relation");
                });
        assertThat(modules.select(module.getId())).isNotNull();
        assertThat(relations.count(Criteria.of().eq("moduleAlias", module.getAlias()))).isEqualTo(1);

        relations.delete(relationId, relations.select(relationId).getVersion());
        assertThat(remove(module, hardDelete)).isEqualTo(1);
        assertThat(modules.select(module.getId())).isNull();
    }

    private int remove(PlatformModule module, boolean hardDelete) {
        AbstractAbilityService<PlatformModule> hardTarget = new AbstractAbilityService<>(
                PlatformModuleService.MODULE_ALIAS, PlatformModule.class, modules.getDao()) {};
        return hardDelete ? hardTarget.delete(module.getId(), module.getVersion())
                : modules.delete(module.getId(), module.getVersion());
    }

    static class RelationSource extends AbstractAbilityService<ModuleMetadataRelation>
            implements SoftDeleteAbility<ModuleMetadataRelation> {
        RelationSource(ModuleMetadataRelationDao dao) {
            super("platform.module_metadata_relation", ModuleMetadataRelation.class, dao);
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableMuYunRepositories(basePackageClasses = {PlatformModuleDao.class, ModuleMetadataRelationDao.class})
    static class TestApplication {
        @Bean DataSource dataSource() {
            return DataSourceBuilder.create().url(postgres.getJdbcUrl()).username(postgres.getUsername())
                    .password(postgres.getPassword()).driverClassName(postgres.getDriverClassName()).build();
        }
        @Bean PlatformModuleService modules(PlatformModuleDao dao) {
            return new PlatformModuleService(dao, event -> {});
        }
        @Bean RelationSource relations(ModuleMetadataRelationDao dao) { return new RelationSource(dao); }
    }
}
