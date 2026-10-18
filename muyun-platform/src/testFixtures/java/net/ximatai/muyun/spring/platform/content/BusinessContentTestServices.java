package net.ximatai.muyun.spring.platform.content;

import net.ximatai.muyun.spring.platform.support.TestBeanProviders;

import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaService;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.platform.module.PlatformModuleActionService;
import org.springframework.beans.factory.ObjectProvider;
import java.util.Optional;
import static org.mockito.Mockito.*;

/** Isolated source tests explicitly use a catalogue without custom action declarations. */
public final class BusinessContentTestServices {
    private BusinessContentTestServices() { }

    public static PlatformModuleActionService undeclaredActions() {
        var actions = mock(PlatformModuleActionService.class);
        when(actions.findDeclaredExecutionPolicy(anyString(), anyString())).thenReturn(Optional.empty());
        return actions;
    }

    public static BusinessContentService content(BusinessContentStore store, ActionExecutionPolicyService policies,
                                                 DataScopeCriteriaService scopes) {
        return content(store, policies, scopes, TestBeanProviders.empty(DynamicRecordService.class));
    }

    public static BusinessContentService content(BusinessContentStore store, ActionExecutionPolicyService policies,
                                                 DataScopeCriteriaService scopes, ObjectProvider<DynamicRecordService> records) {
        return new BusinessContentService(store, policies, scopes, records,
                TestBeanProviders.of(PlatformModuleActionService.class, undeclaredActions()));
    }
}
