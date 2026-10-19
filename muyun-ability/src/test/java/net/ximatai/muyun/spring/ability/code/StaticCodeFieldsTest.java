package net.ximatai.muyun.spring.ability.code;

import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.builder.ColumnType;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class StaticCodeFieldsTest {
    @Test void rejectsNonStringAndNonPersistentCodeDeclarations() {
        assertThatThrownBy(() -> StaticCodeFields.declarations(Numeric.class)).hasMessageContaining("persisted string");
        assertThatThrownBy(() -> StaticCodeFields.declarations(Virtual.class)).hasMessageContaining("persisted string");
        assertThatThrownBy(() -> StaticCodeFields.declarations(Transient.class)).hasMessageContaining("persisted string");
    }
    @Test void rejectsPlatformOwnedFieldsAndNonVarcharStorage() {
        assertThatThrownBy(() -> StaticCodeFields.declarations(PlatformOwned.class)).hasMessageContaining("business VARCHAR");
        assertThatThrownBy(() -> StaticCodeFields.declarations(TextStorage.class)).hasMessageContaining("business VARCHAR");
    }
    @Test void detectsMultiplePrimaryFieldsAcrossInheritance() {
        assertThatThrownBy(() -> StaticCodeFields.declarations(TwoPrimary.class)).hasMessageContaining("Only one PRIMARY");
    }
    @Test void inheritsValidBindingsAndDoesNotExposeMutableCatalogs() {
        var declarations = StaticCodeFields.declarations(Primary.class);
        assertThat(declarations).containsKey("number");
        assertThatThrownBy(declarations::clear).isInstanceOf(UnsupportedOperationException.class);
    }
    static class PlatformOwned { @BusinessCode @Column(type = ColumnType.VARCHAR) String authOrganizationId; }
    static class TextStorage { @BusinessCode @Column(type = ColumnType.TEXT) String number; }
    static class Numeric { @BusinessCode @Column(type = ColumnType.INT) Integer number; }
    static class Transient { @BusinessCode transient String number; }
    static class Virtual { @BusinessCode String number; }
    static class Primary { @BusinessCode(primary = true) @Column(type = ColumnType.VARCHAR) String number; }
    static class TwoPrimary extends Primary { @BusinessCode(primary = true) @Column(type = ColumnType.VARCHAR) String other; }
}
