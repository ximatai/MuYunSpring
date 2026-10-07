<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import type { ConstructionPlanSnapshot, ConstructionProgress, ConstructionTask } from '@muyun/web-contracts';
import { UiButton } from '@muyun/vue-ui-antdv';
import {
  constructionPlanBindings,
  presentConstructionPlan,
  planSections,
  type ConstructionPlanSession,
} from './constructionPlanSession';
const props = defineProps<{ session: ConstructionPlanSession; disabled?: boolean }>();
const error = ref('');
const working = ref(false);
const selected = ref('');
const editing = computed(() => props.session.manualEditing.value);
const editTitle = ref('');
const editGoal = ref('');
const editSections = ref<Record<string, string>>({});
function beginEdit() {
  const content = props.session.current().candidate;
  if (!content) return;
  editTitle.value = content.title;
  editGoal.value = content.goal;
  editSections.value = Object.fromEntries(
    Object.keys(planSections).map((key) => [key, content[key as keyof typeof planSections].join('\n')]),
  );
  props.session.beginManualEdit();
}
function applyEdit() {
  const content = props.session.current().candidate;
  if (!content) return;
  const lines = (value: string) =>
    value
      .split('\n')
      .map((line) => line.trim())
      .filter(Boolean);
  props.session.editManually({
    ...content,
    title: editTitle.value,
    goal: editGoal.value,
    ...Object.fromEntries(Object.entries(editSections.value).map(([key, value]) => [key, lines(value)])),
  });
}
const revisions = ref<ConstructionPlanSnapshot[]>([]);
const progress = ref<ConstructionProgress[]>([]);
watch(
  () => props.session.state.value.generation,
  () => {
    revisions.value = [];
    progress.value = [];
  },
);
const state = computed(() => props.session.state.value);
const delivered = computed(() => state.value.saved?.constructionStatus === 'DELIVERED');
const currentTask = computed(() => props.session.currentTask());
const taskError = ref('');
async function refreshTask() {
  const generation = state.value.generation;
  taskError.value = '';
  if (!state.value.saved || props.session.dirty() || state.value.reviewRequired || delivered.value) return;
  try {
    await props.session.readTask();
  } catch {
    if (generation === state.value.generation) taskError.value = '实际建设进度暂时无法读取，请重试。';
  }
}
watch(() => state.value.generation, refreshTask, { immediate: true });
function progressLabel(object: ConstructionTask['objects'][number]) {
  if (object.complete) return '已验收；后续修改以当前配置为准';
  const value = object.progress;
  if (!value)
    return constructionPlanBindings(state.value.saved).some(
      (binding) => binding.objectKey === object.objectKey,
    )
      ? '已关联模块，当前配置尚未核实'
      : '尚未关联模块，当前配置尚未核实';
  if (value.runtimeStatus !== 'ACTIVE') return '配置已提交，正在等待可用状态确认';
  if (value.needsReview) return '已有配置，需要核对最新变化';
  if (value.entryVisible && value.pagePublished) return '页面和入口已可用，待实际试用';
  if (value.pagePublished) return '页面已发布，访问入口待完成';
  if (object.requirements.some((item) => item.status === 'CONFIGURATION_MISSING'))
    return '已建立，登记内容待补齐，页面尚未发布';
  if (currentTask.value?.unmappedRequirements.length) return '已有配置，本期要求待核对，页面尚未发布';
  return '登记内容已配置，页面尚未发布';
}

const presentation = computed(() => state.value.candidate && presentConstructionPlan(state.value.candidate));
async function run(action: () => unknown) {
  error.value = '';
  working.value = true;
  try {
    await action();
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '方案操作失败';
  } finally {
    working.value = false;
  }
}
</script>
<template>
  <section v-if="currentTask" class="construction-plan" aria-label="当前建设进度">
    <strong>当前建设进度</strong>
    <p v-for="object in currentTask.objects" :key="object.objectKey">
      {{ object.title }}：{{ progressLabel(object) }}
      <span v-if="object.options.some((option) => option.action === 'REVIEW_REQUIREMENTS')">
        仍有要求待商定，尚不能完整交付。
      </span>
    </p>
    <UiButton size="small" :disabled="disabled" @click="refreshTask">刷新实际进度</UiButton>
  </section>
  <p v-if="taskError" role="status">
    {{ taskError }} <UiButton size="small" :disabled="disabled" @click="refreshTask">重试读取进度</UiButton>
  </p>
  <details class="construction-plan">
    <summary>
      {{ delivered ? '历史建设记录' : '本次建设设计'
      }}{{ state.candidate ? `：${state.candidate.title}` : '' }}
      <span v-if="state.candidate" class="construction-plan__persistence">
        {{
          delivered
            ? '已交付 · 后续改进以当前配置为准'
            : session.recovery.value
              ? '保存结果待查询'
              : session.dirty()
                ? '当前修改未保存'
                : state.saved
                  ? `需求已保存 · 第 ${state.saved.revision} 版`
                  : '尚未保存'
        }}
      </span>
    </summary>
    <p v-if="delivered">
      业务已交付。这份记录保留当时的设计与交付结果，不代表当前配置。直接告诉助手要改进什么，无需重新恢复或修改此方案。
    </p>
    <p v-else>在这里商定本次建设的目标与范围。改进已有业务可直接读取当前配置，无需另建方案。</p>
    <p v-if="state.candidate && !delivered">
      {{ state.saved ? `已确认第 ${state.saved.revision} 版` : '尚未确认' }} ·
      {{ session.dirty() ? '有未确认修改' : '与已确认版本一致' }} ·
      {{
        constructionPlanBindings(state.saved).length
          ? '已关联标准模块，当前配置与验收状态以实际查询为准'
          : '尚未关联标准模块'
      }}
    </p>
    <details
      v-if="
        state.saved &&
        (state.saved.initializations.length ||
          state.saved.deliveries.length ||
          state.saved.fieldChanges.length)
      "
    >
      <summary>查看配置提交记录</summary>
      <p v-for="item in state.saved?.initializations ?? []" :key="item.objectKey">
        已初始化：{{ item.moduleAlias }}（依据第 {{ item.planRevision }} 版）{{
          item.planRevision !== state.saved?.revision ? '；需求版本已变化，后续配置须重新核对' : ''
        }}
      </p>
      <p v-for="delivery in state.saved?.deliveries ?? []" :key="delivery.requestId">
        {{ delivery.kind === 'PAGE' ? '页面发布' : '入口创建' }}：{{ delivery.moduleAlias }} · 需求第
        {{ delivery.planRevision }} 版
      </p>
      <p v-for="change in state.saved?.fieldChanges ?? []" :key="change.requestId">
        字段已提交：{{ change.fields.map((field) => field.title).join('、') }}（依据第
        {{ change.planRevision }} 版）{{
          change.planRevision !== state.saved?.revision ? '；需求已修订，须核对已有字段' : ''
        }}；页面和入口状态请查询实际建设进度。
      </p>
    </details>
    <p v-if="state.reviewRequired" role="status">
      目标或范围已修改。原有问题、假设和规则待重新核对，请告诉助手“核对修改后的方案”再确认；不会自动视为已解决。
    </p>
    <UiButton
      v-if="constructionPlanBindings(state.saved).length && !delivered"
      :disabled="disabled || working"
      @click="
        run(async () => {
          const loaded: ConstructionProgress[] = [];
          for (const object of constructionPlanBindings(state.saved))
            if (!state.saved?.deliveredObjectKeys.includes(object.objectKey))
              loaded.push(await session.progress(object.objectKey));
          progress = loaded;
        })
      "
      >查询实际建设进度</UiButton
    >
    <section v-for="item in progress" :key="item.objectKey" aria-label="实际建设进度">
      <p>
        {{ item.moduleAlias }}：页面{{ item.pagePublished ? '已发布' : '未完成' }}，入口{{
          item.entryVisible ? '可见' : '不可见'
        }}，运行态 {{ item.runtimeStatus }}
      </p>
      <p>人工验收：{{ item.acceptanceConfirmed ? '当前基线已确认通过' : '待核对' }}</p>
      <p v-for="line in item.remainingWork" :key="line">{{ line }}</p>
    </section>
    <UiButton
      v-if="state.saved && !delivered && session.facts().governanceAvailable"
      :disabled="disabled || working || session.dirty()"
      @click="run(() => session.readTask())"
      >查看下一步</UiButton
    >
    <details v-if="currentTask?.unmappedRequirements.length">
      <summary>尚未对应业务对象的要求</summary>
      <p v-for="item in currentTask.unmappedRequirements" :key="`${item.section}:${item.index}`">
        {{ item.statement }}：尚未对应。{{ item.explanation }}
      </p>
    </details>
    <section
      v-for="object in session.currentTask()?.objects ?? []"
      :key="object.objectKey"
      aria-label="建设任务"
    >
      <p>{{ object.title }}：{{ object.complete ? '已交付，后续以当前治理配置为准' : '可继续处理的事项' }}</p>
      <ul v-if="!object.complete">
        <li v-for="option in object.options" :key="option.action">{{ option.explanation }}</li>
      </ul>
      <details>
        <summary>逐项核对本期要求</summary>
        <p v-for="(item, index) in object.requirements" :key="index">
          {{ item.statement }}：{{
            {
              UNMAPPED: '尚未对应',
              UNSUPPORTED: '暂不支持',
              CONFIGURATION_MISSING: '所需配置尚未就绪',
              CONFIGURATION_MATCHED: '配置已核对，仍须业务试用',
              MANUAL_RESPONSIBILITY: '按约定由人处理',
            }[item.status]
          }}。{{ item.explanation }}
        </p>
      </details>
    </section>
    <p v-if="session.dirty()">本版变化：{{ session.changes().join('、') }}</p>
    <details v-if="presentation">
      <summary>{{ delivered ? '查看当时的设计' : '审阅当前候选' }}</summary>
      <p>{{ state.candidate?.goal }}</p>
      <details v-for="section in presentation.sections" :key="section.title" :open="section.expanded">
        <summary>{{ section.title }} · {{ section.lines.length }} 项</summary>
        <ul>
          <li v-for="(line, index) in section.lines" :key="index">{{ line }}</li>
        </ul>
      </details>
      <p v-if="!delivered">本次仅确认需求范围，不创建或发布业务配置；已有建设结果保留。</p>
    </details>
    <UiButton
      v-if="state.candidate && !editing && !delivered"
      :disabled="disabled || working"
      @click="run(beginEdit)"
      >修改业务方案</UiButton
    >
    <section v-if="editing" aria-label="修改方案候选">
      <label>方案名称<input v-model="editTitle" maxlength="120" :disabled="disabled || working" /></label>
      <label>业务目标<textarea v-model="editGoal" maxlength="1500" :disabled="disabled || working" /></label>
      <label v-for="(label, key) in planSections" :key="key"
        >{{ label }}（每行一项）<textarea v-model="editSections[key]" :disabled="disabled || working" />
      </label>
      <UiButton :disabled="disabled || working" @click="run(applyEdit)">更新候选，稍后确认</UiButton>
      <UiButton :disabled="disabled || working" @click="session.cancelManualEdit()">取消人工修改</UiButton>
    </section>
    <details v-if="state.saved">
      <summary>已确认历史</summary>
      <UiButton
        :disabled="disabled || working"
        @click="
          run(async () => {
            revisions = await session.history();
          })
        "
        >读取历史版本</UiButton
      >
      <details v-for="revision in revisions" :key="revision.revision">
        <summary>第 {{ revision.revision }} 版 · {{ revision.content.title }}</summary>
        <p v-for="(line, index) in presentConstructionPlan(revision.content).lines" :key="index">
          {{ line }}
        </p>
      </details>
    </details>
    <template v-if="!state.planId">
      <UiButton :disabled="disabled || working" @click="run(() => session.listSaved())"
        >读取已保存方案</UiButton
      >
      <select v-model="selected" aria-label="已保存的建设方案" :disabled="disabled || working">
        <option value="">选择方案</option>
        <option v-for="plan in session.savedPlans.value" :key="plan.planId" :value="plan.planId">
          {{ plan.title }} · 第 {{ plan.revision }} 版
        </option>
      </select>
      <UiButton
        :disabled="disabled || working || !selected || session.dirty()"
        @click="run(() => session.restore(selected))"
        >恢复讨论</UiButton
      >
    </template>
    <p v-if="session.recovery.value">结果未确定，请先查询确认结果；在查明前不能重新提交或修改本次方案。</p>
    <UiButton
      v-if="session.recovery.value"
      :disabled="disabled || working"
      @click="
        run(async () => {
          const result = await session.recovery.value?.();
          if (!result) throw new Error('尚未查到确认结果，请稍后再次查询');
        })
      "
      >查询上次确认结果</UiButton
    >
    <details v-if="session.dirty() && !delivered">
      <summary>放弃未确认修改</summary>
      <p>已确认的历史版本仍然保留，未确认修改将被丢弃。</p>
      <UiButton :disabled="disabled || working" @click="run(() => session.discardCandidate())"
        >放弃本次未确认修改</UiButton
      >
    </details>
    <p v-if="state.planId && !delivered">此对话围绕同一个建设目标；讨论其他独立业务请新建对话。</p>
    <p v-if="error" role="alert">{{ error }}</p>
  </details>
</template>
<style scoped>
.construction-plan {
  padding: 12px;
  border: 1px solid var(--muyun-support-border);
  border-radius: 8px;
  font-size: 13px;
}
.construction-plan__persistence {
  display: block;
  margin-top: 4px;
  font-weight: 500;
}
summary {
  cursor: pointer;
}
p {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
select,
input,
textarea {
  max-width: 100%;
}
label {
  display: grid;
  gap: 4px;
  margin: 8px 0;
}
</style>
