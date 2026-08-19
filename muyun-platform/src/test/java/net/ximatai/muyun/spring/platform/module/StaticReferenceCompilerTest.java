package net.ximatai.muyun.spring.platform.module;



import net.ximatai.muyun.spring.ability.reference.ReferenceTo;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StaticReferenceCompilerTest {
    @Test
    void shouldCompileStrongReferenceFromStaticModelField() {
        List<StaticReferenceDefinition> references = StaticReferenceCompiler.compile(BindingModel.class);

        assertThat(references)
                .containsExactly(
                        new StaticReferenceDefinition("employee", "employeeId", "iam.employee"),
                        new StaticReferenceDefinition("owner_user", "ownerUserId", "iam.user"),
                        new StaticReferenceDefinition("default_ui_control", "defaultUiControlAlias", "iam.user")
                );
    }

    @Test
    void shouldRequireExactlyOneTargetDeclaration() {
        assertThatThrownBy(() -> StaticReferenceCompiler.compile(MissingTargetModel.class))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("exactly one of target or moduleAlias/entityAlias");

        assertThatThrownBy(() -> StaticReferenceCompiler.compile(DuplicateTargetModel.class))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("exactly one of target or moduleAlias/entityAlias");
    }

    @Test
    void shouldRequireTargetServiceModuleAlias() {
        assertThatThrownBy(() -> StaticReferenceCompiler.compile(BadServiceTargetModel.class))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("requires public MODULE_ALIAS");
    }

    private static class BindingModel {
        @ReferenceTo(moduleAlias = "iam", entityAlias = "employee")
        private String employeeId;

        @ReferenceTo(target = UserService.class)
        private String ownerUserId;

        @ReferenceTo(target = UserService.class)
        private String defaultUiControlAlias;
    }

    private static class MissingTargetModel {
        @ReferenceTo
        private String employeeId;
    }

    private static class DuplicateTargetModel {
        @ReferenceTo(target = UserService.class, moduleAlias = "iam", entityAlias = "user")
        private String userId;
    }

    private static class BadServiceTargetModel {
        @ReferenceTo(target = ServiceWithoutModuleAlias.class)
        private String userId;
    }

    public static class UserService {
        public static final String MODULE_ALIAS = "iam.user";
    }

    public static class ServiceWithoutModuleAlias {
    }
}
