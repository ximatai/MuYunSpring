package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.option.OptionBinding;
import net.ximatai.muyun.spring.common.option.DictionaryField;
import net.ximatai.muyun.spring.common.option.OptionItem;
import net.ximatai.muyun.spring.common.option.OptionQuery;
import net.ximatai.muyun.spring.common.option.OptionSource;
import net.ximatai.muyun.spring.common.option.OptionSourceProvider;
import net.ximatai.muyun.spring.common.option.OptionSourceRegistry;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicEntityDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicFieldDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicModuleDescriptor;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModuleFieldOptionServiceTest {
    @Test
    void shouldResolveStaticFieldOptionsThroughOptionSourceRegistry() {
        StaticModuleDefinition definition = StaticModuleDefinition.builder("iam", "iam.employee", "职员")
                .modelClass(Employee.class)
                .build();
        StaticModuleDefinitionCatalog catalog = new StaticModuleDefinitionCatalog(List.of(definition));
        OptionSourceRegistry registry = new OptionSourceRegistry(List.of(new GenderOptionProvider()));
        ModuleFieldOptionService service = new ModuleFieldOptionService(catalog,
                new StaticListableBeanFactory().getBeanProvider(net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService.class),
                registry);

        assertThat(service.options("iam.employee", "gender", true, null))
                .extracting(OptionItem::code, OptionItem::title)
                .containsExactly(tuple("1", "男"), tuple("2", "女"));
    }

    @Test
    void shouldResolveDynamicChildEntityOptionsWhenEntityAliasIsSupplied() {
        DynamicRecordService records = mock(DynamicRecordService.class);
        DynamicModuleDescriptor module = mock(DynamicModuleDescriptor.class);
        DynamicEntityDescriptor main = mock(DynamicEntityDescriptor.class);
        DynamicEntityDescriptor participant = mock(DynamicEntityDescriptor.class);
        DynamicFieldDescriptor examStatus = mock(DynamicFieldDescriptor.class);
        DynamicFieldDescriptor attendanceStatus = mock(DynamicFieldDescriptor.class);
        OptionBinding binding = new OptionBinding(OptionBinding.DICTIONARY_SOURCE, "education.exam_attendance_status");
        when(records.describe("education.exam")).thenReturn(module);
        when(module.mainEntityAlias()).thenReturn("exam");
        when(module.entities()).thenReturn(List.of(main, participant));
        when(main.entityAlias()).thenReturn("exam");
        when(main.fields()).thenReturn(List.of(examStatus));
        when(examStatus.fieldName()).thenReturn("examStatus");
        when(examStatus.optionBinding()).thenReturn(binding);
        when(participant.entityAlias()).thenReturn("exam_participant");
        when(participant.fields()).thenReturn(List.of(attendanceStatus));
        when(attendanceStatus.fieldName()).thenReturn("attendanceStatus");
        when(attendanceStatus.optionBinding()).thenReturn(binding);
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        factory.addBean("dynamicRecordService", records);
        ModuleFieldOptionService service = new ModuleFieldOptionService(
                new StaticModuleDefinitionCatalog(List.of()), factory.getBeanProvider(DynamicRecordService.class),
                new OptionSourceRegistry(List.of(new GenderOptionProvider())));

        assertThat(service.options("education.exam", "exam_participant", "attendanceStatus", false, null))
                .extracting(OptionItem::code, OptionItem::title)
                .containsExactly(tuple("1", "男"), tuple("2", "女"));
        assertThat(service.options("education.exam", "examStatus", false, null))
                .extracting(OptionItem::code, OptionItem::title)
                .containsExactly(tuple("1", "男"), tuple("2", "女"));
        assertThatThrownBy(() -> service.options("education.exam", "unknown_entity", "attendanceStatus", false, null))
                .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformException.class)
                .hasMessageContaining("dynamic entity not found: education.exam.unknown_entity");
    }

    private static org.assertj.core.groups.Tuple tuple(String code, String title) {
        return org.assertj.core.groups.Tuple.tuple(code, title);
    }

    private static class Employee {
        @DictionaryField(source = "iam.gender")
        private String gender;
    }

    private static class GenderOptionProvider implements OptionSourceProvider {
        @Override
        public String sourceType() {
            return OptionBinding.DICTIONARY_SOURCE;
        }

        @Override
        public OptionSource source(OptionBinding binding) {
            return new OptionSource() {
                @Override
                public OptionBinding binding() {
                    return binding;
                }

                @Override
                public List<OptionItem> options(OptionQuery query) {
                    return List.of(new OptionItem("1", "男", true, 10, null),
                            new OptionItem("2", "女", true, 20, null));
                }
            };
        }
    }
}
