package net.ximatai.muyun.spring.platform.web;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.DataScopeAbility;
import net.ximatai.muyun.spring.ability.RecycleBinAbility;
import net.ximatai.muyun.spring.ability.security.FieldCryptoProvider;
import net.ximatai.muyun.spring.ability.security.FieldProtectionAbility;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.common.platform.AllowAllDataScopeCriteriaService;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaService;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.security.EncryptedField;
import net.ximatai.muyun.spring.common.security.FieldMaskingPolicy;
import net.ximatai.muyun.spring.common.security.MaskedField;
import net.ximatai.muyun.spring.platform.deletion.DeletionLogService;
import net.ximatai.muyun.spring.platform.deletion.RecycleBinFacade;
import net.ximatai.muyun.spring.platform.deletion.RecycleBinItem;
import net.ximatai.muyun.spring.platform.deletion.RecycleBinPurgeCoordinator;
import net.ximatai.muyun.spring.platform.deletion.SoftDeleteRestoreCoordinator;
import net.ximatai.muyun.spring.web.WebPageResponse;
import net.ximatai.muyun.spring.web.endpoint.RegisteredWebEndpoint;
import net.ximatai.muyun.spring.web.endpoint.ResolvedWebEndpoint;
import net.ximatai.muyun.spring.web.endpoint.StaticWebOperationTarget;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;

import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RecycleBinOutputProtectionTest {
    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    @SuppressWarnings("unchecked")
    void retainedListFallbackMasksFieldsForExplicitAndGeneratedEndpoints(boolean generated, boolean dataScoped) throws Exception {
        BaseDao<ProtectedRecord, String> dao = mock(BaseDao.class);
        when(dao.pageQuery(any(), any(), any(Sort[].class))).thenAnswer(invocation -> {
            ProtectedRecord row = new ProtectedRecord();
            row.setId("retained");
            row.setDeleted(true);
            row.setPhone("enc:13812345678");
            return PageResult.of(List.of(row), 1, invocation.getArgument(1));
        });
        ProtectedRecords service = dataScoped ? new ScopedProtectedRecords(dao) : new ProtectedRecords(dao);
        RecycleBinFacade facade = new RecycleBinFacade(mock(DeletionLogService.class),
                mock(SoftDeleteRestoreCoordinator.class), mock(RecycleBinPurgeCoordinator.class));
        RecycleBinWeb<ProtectedRecord, ProtectedRecords> web = new RecycleBinWeb<>() {
            @Override public ProtectedRecords service() { return service; }
            @Override public RecycleBinFacade recycleBinFacade() { return facade; }
            @Override public <T> T webScope(Supplier<T> work) { return work.get(); }
        };
        WebPageResponse<RecycleBinItem<?>> response;
        if (generated) {
            StaticListableBeanFactory beans = new StaticListableBeanFactory();
            beans.addBean("recycleBinFacade", facade);
            var runtime = new StaticAbilityOperationRuntime(beans.getBeanProvider(RecycleBinFacade.class));
            var definition = new ResolvedWebEndpoint("test.protected.recycleBinQuery", "test.protected", "query", "query",
                    PlatformAction.RECYCLE_BIN_QUERY, RequestMethod.POST, "/test.protected/recycle-bin/query",
                    ResolvedWebEndpoint.Source.STATIC_ABILITY);
            var endpoint = new RegisteredWebEndpoint(definition,
                    RequestMappingInfo.paths(definition.path()).methods(RequestMethod.POST).build(), this,
                    getClass().getDeclaredMethod("marker"), new StaticWebOperationTarget("test.protected", web, service));
            response = (WebPageResponse<RecycleBinItem<?>>) runtime.execute(endpoint, new MockHttpServletRequest(), null);
        } else {
            response = web.recycleBin(null);
        }
        assertThat(((ProtectedRecord) response.records().getFirst().record()).getPhone()).isEqualTo("138****5678");
        assertThat(service.pageRecycleBin(Criteria.of(), PageRequest.of(1, 20)).getRecords().getFirst().getPhone())
                .as("domain reads remain unmasked business values").isEqualTo("13812345678");
    }

    private void marker() {}

    @Getter @Setter
    static class ProtectedRecord extends StandardEntity {
        @EncryptedField @MaskedField(FieldMaskingPolicy.PHONE)
        private String phone;
    }

    static class ProtectedRecords extends AbstractAbilityService<ProtectedRecord>
            implements RecycleBinAbility<ProtectedRecord>, FieldProtectionAbility<ProtectedRecord> {
        ProtectedRecords(BaseDao<ProtectedRecord, String> dao) { super("test.protected", ProtectedRecord.class, dao); }
        @Override public FieldCryptoProvider fieldCryptoProvider() {
            return new FieldCryptoProvider() {
                @Override public String encrypt(String field, Object value) { return "enc:" + value; }
                @Override public Object decrypt(String field, String value) { return value.substring(4); }
            };
        }
    }

    static class ScopedProtectedRecords extends ProtectedRecords implements DataScopeAbility<ProtectedRecord> {
        ScopedProtectedRecords(BaseDao<ProtectedRecord, String> dao) { super(dao); }
        @Override public DataScopeCriteriaService getDataScopeCriteriaService() { return new AllowAllDataScopeCriteriaService(); }
    }
}
