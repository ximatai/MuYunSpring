package net.ximatai.muyun.spring.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.output.*;
import net.ximatai.muyun.spring.ability.security.FieldProtectionAbility;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.security.FieldOutputContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class WebOutputSupportTest {
    @AfterEach
    void reset() {
        WebOutputSupport.reset();
    }

    @Test
    void viewSurfaceRetainsFieldProtectionAndRestoresNestedAndFailedScopes() {
        var contexts = new ArrayList<RecordOutputContext>();
        WebOutputSupport.configure(new DefaultPlatformRecordOutput(List.of(new RecordOutputTransformer() {
            @Override
            public boolean supports(CrudAbility<?> service, RecordOutputContext context) { return true; }
            @Override
            public <T extends EntityContract> T transformRecord(CrudAbility<T> service, T row, RecordOutputContext context) {
                contexts.add(context);
                return row;
            }
        })));
        var owner = mock(ProtectedOwner.class);
        var row = mock(EntityContract.class);
        WebOutputSupport.withViewSurface(RecordOutputSurface.EXPORT, () -> {
            WebOutputSupport.record(owner, row, FieldOutputContext.VIEW);
            WebOutputSupport.record(owner, row, FieldOutputContext.REFERENCE);
            WebOutputSupport.record(owner, row, FieldOutputContext.LIST);
            assertThatThrownBy(() -> WebOutputSupport.withViewSurface(RecordOutputSurface.FORM, () -> {
                WebOutputSupport.record(owner, row, FieldOutputContext.VIEW);
                throw new IllegalStateException("test");
            })).isInstanceOf(IllegalStateException.class);
            WebOutputSupport.record(owner, row, FieldOutputContext.VIEW);
            return null;
        });
        WebOutputSupport.record(owner, row, FieldOutputContext.VIEW);
        assertThat(contexts).extracting(RecordOutputContext::surface).containsExactly(
                RecordOutputSurface.EXPORT, RecordOutputSurface.REFERENCE, RecordOutputSurface.LIST,
                RecordOutputSurface.FORM, RecordOutputSurface.EXPORT, RecordOutputSurface.VIEW);
        assertThat(contexts).extracting(RecordOutputContext::fieldContext).containsExactly(
                FieldOutputContext.VIEW, FieldOutputContext.REFERENCE, FieldOutputContext.LIST,
                FieldOutputContext.VIEW, FieldOutputContext.VIEW, FieldOutputContext.VIEW);
    }

    @Test
    void exportViewStillRunsViewProtectionBeforeSeparateExportProtection() {
        var owner = mock(ProtectedOwner.class);
        var raw = mock(EntityContract.class);
        var viewMasked = mock(EntityContract.class);
        var exportMasked = mock(EntityContract.class);
        when(owner.maskProtectedFieldsForOutput(raw, FieldOutputContext.VIEW)).thenReturn(viewMasked);
        when(owner.maskProtectedFieldsForOutput(viewMasked, FieldOutputContext.EXPORT)).thenReturn(exportMasked);
        WebOutputSupport.configure(new DefaultPlatformRecordOutput(List.of(new FieldProtectionRecordOutputTransformer())));
        var result = WebOutputSupport.withViewSurface(RecordOutputSurface.EXPORT,
                () -> WebOutputSupport.record(owner, raw, FieldOutputContext.VIEW));
        assertThat(result).isSameAs(viewMasked);
        assertThat(WebOutputSupport.record(owner, result, FieldOutputContext.EXPORT)).isSameAs(exportMasked);
        verify(owner).maskProtectedFieldsForOutput(raw, FieldOutputContext.VIEW);
        verify(owner).maskProtectedFieldsForOutput(viewMasked, FieldOutputContext.EXPORT);
    }

    interface ProtectedOwner extends CrudAbility<EntityContract>, FieldProtectionAbility<EntityContract> {}
}
