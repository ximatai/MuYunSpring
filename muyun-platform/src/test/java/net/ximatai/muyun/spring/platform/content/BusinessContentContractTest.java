package net.ximatai.muyun.spring.platform.content;

import net.ximatai.muyun.database.core.annotation.*;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.*;
import net.ximatai.muyun.spring.common.content.*;
import net.ximatai.muyun.spring.common.identity.*;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.security.MaskedField;
import net.ximatai.muyun.spring.common.platform.*;
import net.ximatai.muyun.spring.dynamic.metadata.*;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusinessContentContractTest {
    @Test void sourceLanguageBelongsToExactRecordAndScopeRestoresAfterFailure() {
        var first = new ContentFixture(); var child = new ContentFixture();
        assertThatThrownBy(() -> {
            try (var input = ContentWriteContext.open(first, Map.of("title", new ContentWriteContext.SourceInput("de-de", null)))) {
                assertThat(ContentWriteContext.inputs(first).get("title").languageTag()).isEqualTo("de-DE");
                assertThat(ContentWriteContext.inputs(child)).isEmpty();
                try (var nested = ContentWriteContext.open(child, Map.of("title", new ContentWriteContext.SourceInput("en", null)))) {
                    assertThat(ContentWriteContext.inputs(first).get("title").languageTag()).isEqualTo("de-DE");
                }
                throw new IllegalStateException("abort");
            }
        }).hasMessage("abort");
        assertThat(ContentWriteContext.inputs(first)).isEmpty();
        assertThatThrownBy(() -> new ContentWriteContext.SourceInput("not_a_language", null)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void dynamicDeclarationsKeepIdentityAcrossFluentCopiesAndRejectUnsafeFields() {
        var policy = new ContentFieldDefinition("stable-key", ContentPolicy.AUTO_TRANSLATE);
        var field = FieldDefinition.text("description", "Description").content(policy).required().indexed().queryable().length(500);
        assertThat(field.content()).isEqualTo(policy);
        var compiled = new StaticEntityDefinitionCompiler().compile("main", "Content", ContentFixture.class);
        assertThat(compiled.fields().stream().filter(value -> value.fieldName().equals("title")).findFirst().orElseThrow().content())
                .isEqualTo(new ContentFieldDefinition("category-name", ContentPolicy.MANUAL_TRANSLATIONS));
        assertThatThrownBy(() -> field.virtual()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> field.dictionary("test", "status")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FieldDefinition.integer("quantity", "Quantity").content(policy)).isInstanceOf(IllegalArgumentException.class);
        var entity = new EntityDefinition("main", "content_main", "Main", List.of(field, FieldDefinition.text("other", "Other").content(policy)));
        assertThatThrownBy(() -> new ModuleDefinitionValidator().validateEntity(entity)).isInstanceOf(ModuleDefinitionException.class);
    }
    @Test void protectedStaticTextCannotCreateAnUnprotectedHistoryCopy() {
        var service = new AbstractAbilityService<ProtectedFixture>("test.protected", ProtectedFixture.class, mock(BaseDao.class)) { };
        assertThatThrownBy(() -> BusinessContentFields.definitions(service, new ProtectedFixture()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unprotected physical text");
    }
    @Test void actionDeniedAndMissingIdentityCannotQueryHistoryStorage() {
        var store = mock(BusinessContentStore.class);
        var policies = mock(ActionExecutionPolicyService.class);
        var service = BusinessContentTestServices.content(store, policies, new AllowAllDataScopeCriteriaService());
        var owner = new AbstractAbilityService<ContentFixture>("test.content", ContentFixture.class, mock(BaseDao.class)) { };
        assertThatThrownBy(() -> service.history(owner, "record", "title", PageRequest.of(1, 20)))
                .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
        assertThatThrownBy(() -> service.sources(owner, "record"))
                .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
        doThrow(new net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException("denied")).when(policies).requireRecordAction(any());
        try (var actor = CurrentUserContext.use(CurrentUser.tenantUser("user", "user", "tenant")); var tenant = TenantContext.use("tenant")) {
            assertThatThrownBy(() -> service.history(owner, "record", "title", PageRequest.of(1, 20)))
                    .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
            assertThatThrownBy(() -> service.sources(owner, "record"))
                    .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
        }
        verifyNoInteractions(store, owner.getDao());
    }
    @Table(name = "protected_content")
    static class ProtectedFixture extends net.ximatai.muyun.spring.common.model.standard.StandardEntity {
        @BusinessContent @MaskedField @Column(name = "secret", type = ColumnType.TEXT)
        String secret;
    }
}
