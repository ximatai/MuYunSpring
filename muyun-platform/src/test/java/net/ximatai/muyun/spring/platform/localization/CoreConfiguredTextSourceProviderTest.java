package net.ximatai.muyun.spring.platform.localization;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.workflow.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CoreConfiguredTextSourceProviderTest {
    @Test void keepsEachPublishedVersionAsAnIndependentOriginalForTranslationCorrections() {
        var definitions = mock(WorkflowDefinitionService.class);
        var versions = mock(WorkflowVersionDao.class);
        var definition = new WorkflowDefinition(); definition.setId("definition");
        when(definitions.list(any(net.ximatai.muyun.database.core.orm.Criteria.class), any(net.ximatai.muyun.database.core.orm.PageRequest.class))).thenReturn(List.of(definition));
        var first = version("published-1", "旧节点");
        var second = version("published-2", "新节点");
        when(versions.query(any(net.ximatai.muyun.database.core.orm.Criteria.class), any(net.ximatai.muyun.database.core.orm.PageRequest.class))).thenReturn(List.of(first, second));
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("definitions", definitions); beans.registerSingleton("versions", versions);
        var provider = new CoreConfiguredTextSourceProvider(beans.getBeanProvider(net.ximatai.muyun.spring.platform.menu.MenuService.class),
                beans.getBeanProvider(net.ximatai.muyun.spring.platform.dictionary.DictionaryItemService.class),
                beans.getBeanProvider(WorkflowDefinitionService.class), beans.getBeanProvider(WorkflowVersionDao.class), new ObjectMapper());
        try (var scope = TenantContext.system("translation maintenance")) {
            assertThat(provider.currentSources("sales.order")).containsExactly(
                    ConfigurationTextSources.workflowNodeTitle("published-1", "approval", "旧节点"),
                    ConfigurationTextSources.workflowNodeTitle("published-2", "approval", "新节点"));
            assertThat(TenantContext.tenantFilterBypassed()).isFalse();
        }
    }
    private WorkflowVersion version(String id, String title) {
        var version = new WorkflowVersion(); version.setId(id); version.setPublishStatus(WorkflowPublishStatus.PUBLISHED);
        version.setSnapshotText("{\"nodes\":[{\"nodeKey\":\"approval\",\"title\":\"" + title + "\"}],\"links\":[]}");
        return version;
    }
}
