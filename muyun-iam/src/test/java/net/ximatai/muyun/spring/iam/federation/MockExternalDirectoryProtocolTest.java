package net.ximatai.muyun.spring.iam.federation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import net.ximatai.muyun.spring.common.runtime.PlatformRuntimeModeProvider;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Conditional;

import java.util.List;
import java.util.Map;

class MockExternalDirectoryProtocolTest {
    @Test void refusesProductionRuntimeAndUsesTheLoginMockGate() {
        assertThatThrownBy(() -> new MockExternalDirectoryProtocol(mock(PlatformRuntimeModeProvider.class)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(MockExternalDirectoryProtocol.class.getAnnotation(Conditional.class).value())
                .containsExactly(MockIdentityProviderCondition.class);
    }
    @Test void pagesSubtreesAndMembershipsWithoutLosingSourceStatus() {
        var mode = mock(PlatformRuntimeModeProvider.class); when(mode.isDevelopment()).thenReturn(true);
        var protocol = new MockExternalDirectoryProtocol(mode);
        var departments = List.of(new ExternalDirectoryProtocol.Department("r",null,"Root"),
                new ExternalDirectoryProtocol.Department("a","r","A"),
                new ExternalDirectoryProtocol.Department("b","r","B"));
        var people = List.of(new ExternalDirectoryProtocol.Person("one","One","1","a",List.of("a","b"),"active",null,null),
                new ExternalDirectoryProtocol.Person("two","Two","2","b",List.of("b"),"disabled",null,null));
        var config = Map.of("directory", DirectorySyncStore.encode(new MockExternalDirectoryProtocol.Data(departments,people,List.of())));
        protocol.validateConfiguration(config);
        var descriptor = new IdentityProviderDescriptor("mock","mock","enterprise","app",null,null,config);
        assertThat(protocol.departments(descriptor,"r",null,1).items()).extracting(ExternalDirectoryProtocol.Department::id).containsExactly("r");
        assertThat(protocol.departments(descriptor,"r","1",1).items()).extracting(ExternalDirectoryProtocol.Department::id).containsExactly("a");
        assertThat(protocol.departments(descriptor,"r","2",1).nextCursor()).isNull();
        assertThat(protocol.departments(descriptor,"a",null,100).items()).extracting(ExternalDirectoryProtocol.Department::id).containsExactly("a");
        assertThat(protocol.departmentPeople(descriptor,"b",null,1).items()).extracting(ExternalDirectoryProtocol.Person::id).containsExactly("one");
        assertThat(protocol.departmentPeople(descriptor,"b","1",1).items()).extracting(ExternalDirectoryProtocol.Person::id).containsExactly("two");
        assertThat(protocol.person(descriptor,"one").departmentIds()).containsExactly("a","b");
        assertThat(protocol.person(descriptor,"two").status()).isEqualTo("disabled");
    }
    @Test void rejectsUntrustworthyFixtureAncestryAndUnknownStatus() {
        var mode = mock(PlatformRuntimeModeProvider.class); when(mode.isDevelopment()).thenReturn(true);
        var protocol = new MockExternalDirectoryProtocol(mode);
        var ancestry = new MockExternalDirectoryProtocol.Data(List.of(new ExternalDirectoryProtocol.Department("root","missing","Root")),List.of(),List.of());
        assertThatThrownBy(() -> protocol.validateConfiguration(Map.of("directory", DirectorySyncStore.encode(ancestry)))).isInstanceOf(IllegalArgumentException.class);
        var data = new MockExternalDirectoryProtocol.Data(List.of(new ExternalDirectoryProtocol.Department("root",null,"Root")),
                List.of(new ExternalDirectoryProtocol.Person("one","One",null,"root",List.of("root"),"unknown",null,null)),List.of());
        var invalid = data;
        assertThatThrownBy(() -> protocol.validateConfiguration(Map.of("directory", DirectorySyncStore.encode(invalid)))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void parentTraversalRejectsCyclesDepthOverflowAndMismatchedDetails() {
        var protocol = mock(ExternalDirectoryProtocol.class);
        var provider = new IdentityProviderDescriptor("mock", "mock", "enterprise", "app", null, null, Map.of());
        when(protocol.department(eq(provider), anyString())).thenAnswer(call -> {
            String id = call.getArgument(1);
            return new ExternalDirectoryProtocol.Department(id, id.equals("a") ? "b" : "a", id);
        });
        assertThatThrownBy(() -> DirectorySyncConfiguration.ancestors(protocol, provider, "a"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cycle or depth limit");
        when(protocol.department(eq(provider), anyString())).thenAnswer(call -> {
            String id = call.getArgument(1);
            return new ExternalDirectoryProtocol.Department(id, Integer.toString(Integer.parseInt(id) + 1), id);
        });
        assertThatThrownBy(() -> DirectorySyncConfiguration.ancestors(protocol, provider, "0"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("depth limit");
        reset(protocol);
        when(protocol.department(provider, "a")).thenReturn(new ExternalDirectoryProtocol.Department("forged", null, "A"));
        assertThatThrownBy(() -> DirectorySyncConfiguration.ancestors(protocol, provider, "a"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("MISMATCH");
    }

}
