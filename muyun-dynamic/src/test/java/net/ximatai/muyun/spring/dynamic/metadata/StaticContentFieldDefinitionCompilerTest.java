package net.ximatai.muyun.spring.dynamic.metadata;

import net.ximatai.muyun.database.core.annotation.*;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.content.*;
import net.ximatai.muyun.spring.common.model.standard.StandardEnabledTreeEntity;
import net.ximatai.muyun.spring.common.security.MaskedField;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class StaticContentFieldDefinitionCompilerTest {
    @Test void inheritedTitleAndLocalTextShareOneValidatedContractWithoutOptingInTheBaseModel() {
        var policies = StaticContentFieldDefinitionCompiler.resolve(Category.class);
        assertThat(policies).containsOnlyKeys("title", "description");
        assertThat(policies.get("title")).isEqualTo(new ContentFieldDefinition("category-name", ContentPolicy.MANUAL_TRANSLATIONS));
        var compiled = new StaticEntityDefinitionCompiler().compile("category", "Category", Category.class);
        assertThat(compiled.fields()).filteredOn(field -> field.content().enabled())
                .allSatisfy(field -> assertThat(field.content()).isEqualTo(policies.get(field.fieldName())));
        assertThat(StaticContentFieldDefinitionCompiler.resolve(StandardEnabledTreeEntity.class)).isEmpty();
        assertThatThrownBy(() -> policies.clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void ambiguousUnknownProtectedAndStructuralDeclarationsFailBeforeStorageOrPresentation() {
        for (var type : new Class<?>[]{Missing.class, Duplicate.class, DuplicateKey.class, Shadowed.class,
                Protected.class, Audit.class, TreeLink.class, ShadowedStandardTitle.class}) {
            assertThatThrownBy(() -> StaticContentFieldDefinitionCompiler.resolve(type))
                    .as(type.getSimpleName()).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Table(name = "content_category_contract")
    @BusinessContent(field = "title", value = ContentPolicy.MANUAL_TRANSLATIONS, key = "category-name")
    static class Category extends StandardEnabledTreeEntity {
        @BusinessContent @Column(name = "description", type = ColumnType.TEXT)
        String description;
    }
    @BusinessContent(field = "unknown") static class Missing extends StandardEnabledTreeEntity { }
    @BusinessContent(field = "title") @BusinessContent(field = "title")
    static class Duplicate extends StandardEnabledTreeEntity { }
    @BusinessContent(field = "title", key = "same")
    static class DuplicateKey extends StandardEnabledTreeEntity {
        @BusinessContent(key = "same") @Column(name = "note", type = ColumnType.TEXT) String note;
    }
    static class Shadowed extends Category { String title; }
    @BusinessContent(field = "title") static class ShadowedStandardTitle extends StandardEnabledTreeEntity {
        @Column(name = "title", type = ColumnType.TEXT) String title;
    }
    @BusinessContent(field = "secret") static class Protected {
        @MaskedField @Column(name = "secret", type = ColumnType.TEXT) String secret;
    }
    @BusinessContent(field = "createdBy") static class Audit extends StandardEnabledTreeEntity { }
    @BusinessContent(field = "parentId") static class TreeLink extends StandardEnabledTreeEntity { }
}
