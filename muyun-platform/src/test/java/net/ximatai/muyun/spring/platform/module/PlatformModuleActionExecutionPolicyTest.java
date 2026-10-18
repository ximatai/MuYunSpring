package net.ximatai.muyun.spring.platform.module;

import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionLevel;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlatformModuleActionExecutionPolicyTest {
    @Test void declaredPolicyCanDescribeAnEmbeddedProducerWithoutGrantingItsDisabledStandaloneEntry() {
        var service = mock(PlatformModuleActionService.class, CALLS_REAL_METHODS);
        var action = new PlatformModuleAction();
        action.setModuleAlias("test.line"); action.setActionCode("view"); action.setActionLevel(EntityActionLevel.RECORD);
        action.setPermissionActionCode("classifiedView"); action.setEnabled(false);
        doReturn(action).when(service).findByModuleAliasAndActionCode("test.line", "view");
        doReturn(true).when(service).effectiveDataAuth(action);
        var policy = service.findDeclaredExecutionPolicy("test.line", "view").orElseThrow();
        assertThat(policy.permissionActionCode()).isEqualTo("classifiedView");
        assertThat(policy.dataAuth()).isTrue();
        assertThatThrownBy(() -> service.requireExecutionPolicy("test.line", "view"))
                .isInstanceOf(PlatformAccessDeniedException.class);
        doReturn(null).when(service).findByModuleAliasAndActionCode("test.line", "view");
        assertThat(service.findDeclaredExecutionPolicy("test.line", "view")).isEmpty();
    }
}
