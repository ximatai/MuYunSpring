<script setup lang="ts">
import { computed, ref } from 'vue';
import {
  ManagementExplorerColumn,
  ManagementWorkspace,
  RecordDetailPanel,
  RecordExplorerPanel,
  RecordListExplorer,
  providePageLayout,
  type RecordListExplorerRecord,
} from '../../src/platform-components';

providePageLayout('workspace');

const selectedEntityId = ref('exam_participant');
const entities: RecordListExplorerRecord[] = [
  { id: 'exam', title: '考试', code: 'exam' },
  { id: 'exam_participant', title: '参考学生', code: 'exam_participant' },
  ...Array.from({ length: 32 }, (_, index) => ({
    id: `exam_participant_${index + 1}`,
    title: `参考学生 ${index + 1}`,
    code: `exam_participant_${index + 1}`,
  })),
];
const fields = [
  ['考试', 'examId', 'string'],
  ['学生 ID', 'studentId', 'string'],
  ['学号', 'studentNo', 'string'],
  ['学生姓名', 'studentName', 'string'],
  ['成绩', 'score', 'decimal'],
  ['参考状态', 'attendanceStatus', 'string'],
];
const selectedEntity = computed(() => entities.find((entity) => entity.id === selectedEntityId.value));
</script>

<template>
  <main class="browser-management-workspace-host">
    <ManagementWorkspace class="browser-management-workspace" :explorer-count="1" list-surface>
      <ManagementExplorerColumn>
        <RecordExplorerPanel title="实体">
          <RecordListExplorer
            :records="entities"
            :selected-id="selectedEntityId"
            @select="selectedEntityId = String($event.id)"
          />
        </RecordExplorerPanel>
      </ManagementExplorerColumn>
      <RecordDetailPanel :title="selectedEntity?.title ?? '参考学生'" subtitle="exam_participant">
        <section class="browser-field-section">
          <h3>业务字段</h3>
          <table>
            <thead>
              <tr>
                <th>字段</th>
                <th>字段名</th>
                <th>字段规格</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="field in fields" :key="field[1]">
                <td>{{ field[0] }}</td>
                <td>{{ field[1] }}</td>
                <td>{{ field[2] }}</td>
              </tr>
            </tbody>
          </table>
        </section>
      </RecordDetailPanel>
    </ManagementWorkspace>
  </main>
</template>

<style scoped>
.browser-management-workspace-host {
  height: 700px;
  padding: 12px;
  background: var(--muyun-support-canvas);
}
.browser-management-workspace {
  height: 100%;
}
.browser-field-section {
  min-height: 920px;
}
.browser-field-section h3 {
  margin: 0 0 12px;
}
.browser-field-section table {
  width: 100%;
  border-collapse: collapse;
}
.browser-field-section th,
.browser-field-section td {
  padding: 12px;
  border-bottom: 1px solid var(--muyun-border);
  text-align: left;
}
</style>
