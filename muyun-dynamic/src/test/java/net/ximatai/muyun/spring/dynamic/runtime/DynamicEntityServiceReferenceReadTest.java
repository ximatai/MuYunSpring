package net.ximatai.muyun.spring.dynamic.runtime;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import net.ximatai.muyun.spring.ability.reference.ReferenceReadObserver;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.ability.security.FieldCryptoProvider;
import net.ximatai.muyun.spring.ability.security.FieldSigner;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.time.PlatformTimeService;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityReferenceDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityReferenceLoadDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityReferencedByDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinitionValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DynamicEntityServiceReferenceReadTest {
    @AfterEach
    void tearDown() {
        PlatformAbilityRuntime.resetReferenceTargetResolver();
        PlatformAbilityRuntime.resetReferenceReadObserver();
    }

    @Test
    void shouldBatchDynamicListReferenceReadsFromAStaticTarget() {
        EntityDefinition contract = new EntityDefinition("contract", "sales_contract", "合同",
                List.of(FieldDefinition.string("title", "标题")), java.util.Set.of(EntityCapability.REFERENCE));
        EntityDefinition line = new EntityDefinition("line", "sales_line", "明细",
                List.of(FieldDefinition.string("contractId", "合同").column("contract_id")));
        ModuleDefinition module = ModuleDefinition.builder("sales.order", "订单")
                .entities(List.of(contract, line))
                .references(List.of(EntityReferenceDefinition.to("line", "contractId", ReferenceTarget.of("sales.order", "contract"))
                        .withProjection("title", "contractTitle")
                        .withProjection("title", "contractName")))
                .build();
        DynamicRecord first = new DynamicRecord(line).setValue("contractId", "contract-1");
        DynamicRecord second = new DynamicRecord(line).setValue("contractId", "contract-2");
        DynamicRecordDao sourceDao = mock(DynamicRecordDao.class);
        when(sourceDao.getEntity()).thenReturn(line);
        when(sourceDao.query(any(Criteria.class), any(PageRequest.class), any(Sort[].class)))
                .thenReturn(List.of(first, second));

        @SuppressWarnings("unchecked")
        ReferenceAbility targetAbility = mock(ReferenceAbility.class);
        when(targetAbility.projections(List.of("contract-1", "contract-2"), List.of("title")))
                .thenReturn(Map.of("contract-1", Map.of("title", "合同一"),
                        "contract-2", Map.of("title", "合同二")));
        PlatformAbilityRuntime.configureReferenceTargetResolver(target ->
                ReferenceTarget.of("sales.order", "contract").equals(target)
                        ? java.util.Optional.of(targetAbility)
                        : java.util.Optional.empty());

        DynamicEntityService service = new DynamicEntityService(sourceDao, "sales.order", DynamicRecordLifecycle.NONE,
                module, ignored -> { throw new IllegalStateException("relations are not used"); },
                ignored -> { throw new IllegalStateException("target is static"); }, null, DynamicFieldValueValidator.NONE,
                FieldCryptoProvider.UNAVAILABLE, FieldSigner.UNAVAILABLE, new PlatformTimeService());

        List<DynamicRecord> records = service.list(Criteria.of(), PageRequest.of(1, 20));

        assertThat(records).extracting(record -> record.getValue("contractTitle"))
                .containsExactly("合同一", "合同二");
        verify(targetAbility, times(1)).projections(List.of("contract-1", "contract-2"), List.of("title"));
    }

    @Test
    void shouldLoadDynamicReferencePathAcrossTypedHops() {
        EntityDefinition line = new EntityDefinition("line", "sales_line", "明细",
                List.of(FieldDefinition.string("classroomId", "班级").column("classroom_id")));
        ModuleDefinition module = ModuleDefinition.builder("sales.order", "订单")
                .entities(List.of(line))
                .references(List.of(EntityReferenceDefinition.to("line", "classroomId", "education.school.classroom")))
                .referenceLoads(List.of(new EntityReferenceLoadDefinition("line", "classroomId", "title", "assistantTitle")
                        .withHop(ReferenceTarget.of("education.school", "teacher"), "homeroomTeacherId")
                        .withHop(ReferenceTarget.of("education.school", "student"), "studentAssistantId")))
                .build();
        new ModuleDefinitionValidator().validate(module);
        DynamicRecord record = new DynamicRecord(line).setValue("classroomId", "classroom-1");
        DynamicRecordDao sourceDao = mock(DynamicRecordDao.class);
        when(sourceDao.getEntity()).thenReturn(line);
        when(sourceDao.query(any(Criteria.class), any(PageRequest.class), any(Sort[].class))).thenReturn(List.of(record));

        @SuppressWarnings("unchecked") ReferenceAbility classroom = mock(ReferenceAbility.class);
        @SuppressWarnings("unchecked") ReferenceAbility teacher = mock(ReferenceAbility.class);
        @SuppressWarnings("unchecked") ReferenceAbility student = mock(ReferenceAbility.class);
        when(classroom.projections(List.of("classroom-1"), List.of("homeroomTeacherId")))
                .thenReturn(Map.of("classroom-1", Map.of("homeroomTeacherId", "teacher-1")));
        when(teacher.projections(List.of("teacher-1"), List.of("studentAssistantId")))
                .thenReturn(Map.of("teacher-1", Map.of("studentAssistantId", "student-1")));
        when(student.projections(List.of("student-1"), List.of("title")))
                .thenReturn(Map.of("student-1", Map.of("title", "助理小李")));
        List<ReferenceReadObserver.ProjectionRequest> observations = new java.util.ArrayList<>();
        PlatformAbilityRuntime.configureReferenceReadObserver(observations::add);
        PlatformAbilityRuntime.configureReferenceTargetResolver(target -> switch (target.qualifiedName()) {
            case "education.school.classroom" -> java.util.Optional.of(classroom);
            case "education.school.teacher" -> java.util.Optional.of(teacher);
            case "education.school.student" -> java.util.Optional.of(student);
            default -> java.util.Optional.empty();
        });

        DynamicEntityService service = new DynamicEntityService(sourceDao, "sales.order", DynamicRecordLifecycle.NONE,
                module, ignored -> { throw new IllegalStateException("relations are not used"); },
                ignored -> { throw new IllegalStateException("targets are static"); }, null, DynamicFieldValueValidator.NONE,
                FieldCryptoProvider.UNAVAILABLE, FieldSigner.UNAVAILABLE, new PlatformTimeService());

        assertThat(service.list(Criteria.of(), PageRequest.of(1, 20)).getFirst().getValue("assistantTitle"))
                .isEqualTo("助理小李");
        assertThat(observations).extracting(ReferenceReadObserver.ProjectionRequest::target)
                .containsExactly(ReferenceTarget.of("education.school", "classroom"),
                        ReferenceTarget.of("education.school", "teacher"),
                        ReferenceTarget.of("education.school", "student"));
    }

    @Test
    void shouldPopulateDynamicReferencedByCollection() {
        EntityDefinition classroom = new EntityDefinition("classroom", "edu_classroom", "班级",
                List.of(FieldDefinition.titleField().required()), java.util.Set.of(EntityCapability.REFERENCE));
        EntityDefinition member = new EntityDefinition("member", "edu_member", "成员",
                List.of(FieldDefinition.string("classroomId", "班级").column("classroom_id")));
        ModuleDefinition module = ModuleDefinition.builder("education.school", "教学")
                .entities(List.of(classroom, member))
                .references(List.of(EntityReferenceDefinition.to("member", "classroomId", "education.school.classroom")))
                .referencedBys(List.of(new EntityReferencedByDefinition("classroom", "member", "classroomId", "members")))
                .build();
        new ModuleDefinitionValidator().validate(module);
        DynamicRecord firstClassroom = new DynamicRecord(classroom);
        firstClassroom.setId("classroom-1");
        DynamicRecord secondClassroom = new DynamicRecord(classroom);
        secondClassroom.setId("classroom-2");
        DynamicRecord firstMember = new DynamicRecord(member);
        firstMember.setId("member-1");
        firstMember.setValue("classroomId", "classroom-1");
        DynamicRecord secondMember = new DynamicRecord(member);
        secondMember.setId("member-2");
        secondMember.setValue("classroomId", "classroom-2");
        DynamicRecordDao classroomDao = mock(DynamicRecordDao.class);
        when(classroomDao.getEntity()).thenReturn(classroom);
        when(classroomDao.query(any(Criteria.class), any(PageRequest.class), any(Sort[].class)))
                .thenReturn(List.of(firstClassroom, secondClassroom));
        DynamicEntityService memberService = mock(DynamicEntityService.class);
        when(memberService.list(any(Criteria.class))).thenReturn(List.of(firstMember, secondMember));

        DynamicEntityService service = new DynamicEntityService(classroomDao, "education.school", DynamicRecordLifecycle.NONE,
                module, alias -> "member".equals(alias) ? memberService : null,
                ignored -> { throw new IllegalStateException("targets are not read"); }, null, DynamicFieldValueValidator.NONE,
                FieldCryptoProvider.UNAVAILABLE, FieldSigner.UNAVAILABLE, new PlatformTimeService());

        List<DynamicRecord> classrooms = service.list(Criteria.of(), PageRequest.of(1, 20));

        assertThat((List<Object>) classrooms.getFirst().getValue("members")).containsExactly(firstMember);
        assertThat((List<Object>) classrooms.get(1).getValue("members")).containsExactly(secondMember);
        verify(memberService, times(1)).list(any(Criteria.class));
    }

    @Test
    void shouldPopulateDynamicReferenceVirtualFieldsOnSelect() {
        EntityDefinition classroom = new EntityDefinition("classroom", "edu_classroom", "班级",
                List.of(FieldDefinition.titleField().required(),
                        FieldDefinition.string("teacherId", "班主任").column("teacher_id")),
                java.util.Set.of(EntityCapability.REFERENCE));
        EntityDefinition teacher = new EntityDefinition("teacher", "edu_teacher", "教师",
                List.of(FieldDefinition.titleField().required(),
                        FieldDefinition.string("assistantId", "助理").column("assistant_id")),
                java.util.Set.of(EntityCapability.REFERENCE));
        EntityDefinition assistant = new EntityDefinition("assistant", "edu_assistant", "助理",
                List.of(FieldDefinition.titleField().required()), java.util.Set.of(EntityCapability.REFERENCE));
        EntityDefinition member = new EntityDefinition("member", "edu_member", "成员",
                List.of(FieldDefinition.string("classroomId", "班级").column("classroom_id")));
        ModuleDefinition module = ModuleDefinition.builder("education.school", "教学")
                .entities(List.of(classroom, teacher, assistant, member))
                .references(List.of(
                        EntityReferenceDefinition.to("member", "classroomId", "education.school.classroom"),
                        EntityReferenceDefinition.to("classroom", "teacherId", "education.school.teacher"),
                        EntityReferenceDefinition.to("teacher", "assistantId", "education.school.assistant")))
                .referenceLoads(List.of(new EntityReferenceLoadDefinition("classroom", "teacherId", "title", "assistantTitle")
                        .withHop(ReferenceTarget.of("education.school", "assistant"), "assistantId")))
                .referencedBys(List.of(new EntityReferencedByDefinition("classroom", "member", "classroomId", "members")))
                .build();
        new ModuleDefinitionValidator().validate(module);
        DynamicRecord record = new DynamicRecord(classroom);
        record.setId("classroom-1");
        record.setValue("teacherId", "teacher-1");
        DynamicRecord memberRecord = new DynamicRecord(member);
        memberRecord.setId("member-1");
        memberRecord.setValue("classroomId", "classroom-1");
        DynamicRecordDao classroomDao = mock(DynamicRecordDao.class);
        when(classroomDao.getEntity()).thenReturn(classroom);
        when(classroomDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(record));
        DynamicEntityService memberService = mock(DynamicEntityService.class);
        when(memberService.list(any(Criteria.class))).thenReturn(List.of(memberRecord));
        @SuppressWarnings("unchecked") ReferenceAbility<?> teacherAbility = mock(ReferenceAbility.class);
        @SuppressWarnings("unchecked") ReferenceAbility<?> assistantAbility = mock(ReferenceAbility.class);
        when(teacherAbility.projections(List.of("teacher-1"), List.of("assistantId")))
                .thenReturn(Map.of("teacher-1", Map.of("assistantId", "assistant-1")));
        when(assistantAbility.projections(List.of("assistant-1"), List.of("title")))
                .thenReturn(Map.of("assistant-1", Map.of("title", "助理小李")));
        PlatformAbilityRuntime.configureReferenceTargetResolver(target -> switch (target.qualifiedName()) {
            case "education.school.teacher" -> java.util.Optional.of(teacherAbility);
            case "education.school.assistant" -> java.util.Optional.of(assistantAbility);
            default -> java.util.Optional.empty();
        });
        DynamicEntityService service = new DynamicEntityService(classroomDao, "education.school", DynamicRecordLifecycle.NONE,
                module, alias -> "member".equals(alias) ? memberService : null,
                ignored -> { throw new IllegalStateException("targets are not read"); }, null, DynamicFieldValueValidator.NONE,
                FieldCryptoProvider.UNAVAILABLE, FieldSigner.UNAVAILABLE, new PlatformTimeService());

        DynamicRecord selected = service.select("classroom-1");

        assertThat(selected.getValue("assistantTitle")).isEqualTo("助理小李");
        assertThat((List<Object>) selected.getValue("members")).containsExactly(memberRecord);
    }

    @Test
    void shouldRejectDynamicMultiHopReferenceLoadWithManySource() {
        EntityDefinition source = new EntityDefinition("source", "edu_source", "来源",
                List.of(FieldDefinition.string("classroomIds", "班级").column("classroom_ids")));
        EntityDefinition classroom = new EntityDefinition("classroom", "edu_classroom", "班级",
                List.of(FieldDefinition.titleField().required(),
                        FieldDefinition.string("teacherId", "班主任").column("teacher_id")),
                java.util.Set.of(EntityCapability.REFERENCE));
        EntityDefinition teacher = new EntityDefinition("teacher", "edu_teacher", "教师",
                List.of(FieldDefinition.titleField().required()), java.util.Set.of(EntityCapability.REFERENCE));
        ModuleDefinition module = ModuleDefinition.builder("education.school", "教学")
                .entities(List.of(source, classroom, teacher))
                .references(List.of(
                        EntityReferenceDefinition.to("source", "classroomIds", "education.school.classroom").many(),
                        EntityReferenceDefinition.to("classroom", "teacherId", "education.school.teacher")))
                .referenceLoads(List.of(new EntityReferenceLoadDefinition("source", "classroomIds", "title", "teacherTitle")
                        .withHop(ReferenceTarget.of("education.school", "teacher"), "teacherId")))
                .build();

        assertThatThrownBy(() -> new ModuleDefinitionValidator().validate(module))
                .hasMessageContaining("reference load source must have cardinality ONE");
    }
}
