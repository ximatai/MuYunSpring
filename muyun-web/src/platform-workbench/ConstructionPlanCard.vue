<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import type { ConstructionPlanSnapshot, ConstructionProgress } from '@muyun/web-contracts';
import { UiButton } from '@muyun/vue-ui-antdv';
import { presentConstructionPlan, type ConstructionPlanSession } from './constructionPlanSession';
const props = defineProps<{ session: ConstructionPlanSession; disabled?: boolean }>();
const error = ref('');
const working = ref(false);
const selected = ref('');
const editing = computed(() => props.session.manualEditing.value);
const editTitle = ref('');
const editGoal = ref('');
const editScope = ref('');
const editAcceptance = ref('');
function beginEdit() {
  const content = props.session.current().candidate;
  if (!content) return;
  editTitle.value = content.title;
  editGoal.value = content.goal;
  editScope.value = content.inScope.join('\n');
  editAcceptance.value = content.acceptanceExamples.join('\n');
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
    inScope: lines(editScope.value),
    acceptanceExamples: lines(editAcceptance.value),
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
  <details class="construction-plan">
    <summary>
      业务建设方案{{ state.candidate ? `：${state.candidate.title}` : '' }}
      <span v-if="state.candidate" class="construction-plan__persistence">
        {{
          session.recovery.value
            ? '保存结果待查询'
            : session.dirty()
              ? '当前修改未保存'
              : state.saved
                ? `需求已保存 · 第 ${state.saved.revision} 版`
                : '尚未保存'
        }}
      </span>
    </summary>
    <p>与助手讨论业务目标、范围和验收方式，在对话中确认方案。确认需求不会发布业务配置。</p>
    <p v-if="state.candidate">
      {{ state.saved ? `已确认第 ${state.saved.revision} 版` : '尚未确认' }} ·
      {{ session.dirty() ? '有未确认修改' : '与已确认版本一致' }} ·
      {{
        state.saved?.deliveries.length
          ? '已有页面或入口提交，请查询实际建设和验收状态'
          : state.saved?.fieldChanges.length
            ? '已有字段提交，页面与访问入口仍待建设'
            : state.saved?.constructionStatus === 'INITIALIZED'
              ? '已初始化模块，字段与页面仍待建设'
              : '尚未建设'
      }}
    </p>
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
    <p v-if="state.reviewRequired" role="status">
      目标或范围已修改。原有问题、假设和规则待重新核对，请告诉助手“核对修改后的方案”再确认；不会自动视为已解决。
    </p>
    <UiButton
      v-if="state.saved?.initializations.length"
      :disabled="disabled || working"
      @click="
        run(async () => {
          const loaded: ConstructionProgress[] = [];
          for (const object of state.saved?.initializations ?? [])
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
      v-if="state.saved && session.facts().initializationAvailable"
      :disabled="disabled || working || session.dirty()"
      @click="run(() => session.readTask())"
      >查看下一步</UiButton
    >
    <section
      v-for="object in session.currentTask()?.objects ?? []"
      :key="object.objectKey"
      aria-label="建设任务"
    >
      <p>{{ object.title }}：{{ object.complete ? '当前配置已验收' : '可继续处理的事项' }}</p>
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
              MANUAL_CHECK_REQUIRED: '需要人工核验',
            }[item.status]
          }}。{{ item.explanation }}
        </p>
      </details>
    </section>
    <p v-if="session.dirty()">本版变化：{{ session.changes().join('、') }}</p>
    <details v-if="presentation">
      <summary>审阅当前候选</summary>
      <p v-for="(line, index) in presentation.lines" :key="index">{{ line }}</p>
    </details>
    <UiButton v-if="state.candidate && !editing" :disabled="disabled || working" @click="run(beginEdit)"
      >修改目标与范围</UiButton
    >
    <section v-if="editing" aria-label="修改方案候选">
      <label>方案名称<input v-model="editTitle" maxlength="120" :disabled="disabled || working" /></label>
      <label>业务目标<textarea v-model="editGoal" maxlength="1500" :disabled="disabled || working" /></label>
      <label>本期范围（每行一项）<textarea v-model="editScope" :disabled="disabled || working" /></label>
      <label>验收例子（每行一项）<textarea v-model="editAcceptance" :disabled="disabled || working" /></label>
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
    <details v-if="state.candidate">
      <summary>放弃当前候选 / 另建方案</summary>
      <p>已确认的历史版本仍然保留，未确认修改将被丢弃。</p>
      <UiButton :disabled="disabled || working" @click="run(() => session.newPlan())"
        >放弃候选并开始新方案</UiButton
      >
    </details>
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
