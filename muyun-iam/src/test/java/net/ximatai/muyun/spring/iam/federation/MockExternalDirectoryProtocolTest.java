package net.ximatai.muyun.spring.iam.federation;

import net.ximatai.muyun.spring.common.runtime.PlatformRuntimeModeProvider;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MockExternalDirectoryProtocolTest {
    @Test void refusesProductionRuntimeAndUsesTheLoginMockGate() {
        assertThatThrownBy(() -> new MockExternalDirectoryProtocol(mock(PlatformRuntimeModeProvider.class)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(MockExternalDirectoryProtocol.class.getAnnotation(org.springframework.context.annotation.Conditional.class).value())
                .containsExactly(MockIdentityProviderCondition.class);
    }
    @Test void pagesSubtreesAndMembershipsWithoutLosingSourceStatus() {
        var mode = mock(PlatformRuntimeModeProvider.class); when(mode.isDevelopment()).thenReturn(true);
        var protocol = new MockExternalDirectoryProtocol(mode);
        var departments = List.of(new ExternalDirectoryProtocol.Department("r",null,"Root",List.of()),
                new ExternalDirectoryProtocol.Department("a","r","A",List.of("r")),
                new ExternalDirectoryProtocol.Department("b","r","B",List.of("r")));
        var people = List.of(new ExternalDirectoryProtocol.Person("one","One","1","a",List.of("a","b"),"active",null,null),
                new ExternalDirectoryProtocol.Person("two","Two","2","b",List.of("b"),"disabled",null,null));
        var config = Map.of("directory", DirectorySyncStore.encode(new MockExternalDirectoryProtocol.Data(departments,people,List.of())));
        protocol.validateConfiguration(config);
        var descriptor = new IdentityProviderDescriptor("mock","mock","enterprise","app",null,null,config);
        assertThat(protocol.departments(descriptor,"r",null,1).items()).extracting(ExternalDirectoryProtocol.Department::id).containsExactly("r");
        assertThat(protocol.departments(descriptor,"r","1",1).items()).extracting(ExternalDirectoryProtocol.Department::id).containsExactly("a");
        assertThat(protocol.departments(descriptor,"r","2",1).nextCursor()).isNull();
        assertThat(protocol.departments(descriptor,"a",null,100).items()).extracting(ExternalDirectoryProtocol.Department::id).containsExactly("a");
        assertThat(protocol.departmentPeople(descriptor,"b",null,1).items()).containsExactly("one");
        assertThat(protocol.departmentPeople(descriptor,"b","1",1).items()).containsExactly("two");
        assertThat(protocol.person(descriptor,"one").departmentIds()).containsExactly("a","b");
        assertThat(protocol.person(descriptor,"two").status()).isEqualTo("disabled");
    }
    @Test void rejectsUntrustworthyFixtureAncestryAndUnknownStatus() {
        var mode = mock(PlatformRuntimeModeProvider.class); when(mode.isDevelopment()).thenReturn(true);
        var protocol = new MockExternalDirectoryProtocol(mode);
        var ancestry = new MockExternalDirectoryProtocol.Data(List.of(new ExternalDirectoryProtocol.Department("root",null,"Root",List.of("forged"))),List.of(),List.of());
        assertThatThrownBy(() -> protocol.validateConfiguration(Map.of("directory", DirectorySyncStore.encode(ancestry)))).isInstanceOf(IllegalArgumentException.class);
        var data = new MockExternalDirectoryProtocol.Data(List.of(new ExternalDirectoryProtocol.Department("root",null,"Root",List.of())),
                List.of(new ExternalDirectoryProtocol.Person("one","One",null,"root",List.of("root"),"unknown",null,null)),List.of());
        var invalid = data;
        assertThatThrownBy(() -> protocol.validateConfiguration(Map.of("directory", DirectorySyncStore.encode(invalid)))).isInstanceOf(IllegalArgumentException.class);
    }
}
