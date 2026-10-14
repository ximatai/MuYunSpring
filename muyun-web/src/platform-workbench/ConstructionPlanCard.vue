<script setup lang="ts">
import { computed, ref, shallowRef, watch } from 'vue';
import type { ConstructionPlanSnapshot, ConstructionProgress, ConstructionTask } from '@muyun/web-contracts';
import { UiButton } from '@muyun/vue-ui-antdv';
import { AppError, userFacingErrorMessage } from '@muyun/web-core';
import {
  constructionPlanBindings,
  presentConstructionPlan,
  planSections,
  localizedPlanSections,
  type ConstructionPlanSession,
} from './constructionPlanSession';
const props = defineProps<{ session: ConstructionPlanSession; disabled?: boolean }>();
const language = props.session.language;
const sections = computed(() => localizedPlanSections(language));
const error = shallowRef<() => string>();
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
const taskError = ref(false);
async function refreshTask() {
  const generation = state.value.generation;
  taskError.value = false;
  if (!state.value.saved || props.session.dirty() || state.value.reviewRequired || delivered.value) return;
  try {
    await props.session.readTask();
  } catch {
    if (generation === state.value.generation) taskError.value = true;
  }
}
watch(() => state.value.generation, refreshTask, { immediate: true });
function progressLabel(object: ConstructionTask['objects'][number]) {
  if (object.complete)
    return language.t(
      'platform.screen.ConstructionPlanCard.accepted-use-current-configuration-for-later-changes',
      '已验收；后续修改以当前配置为准',
    );
  const value = object.progress;
  if (!value)
    return constructionPlanBindings(state.value.saved).some(
      (binding) => binding.objectKey === object.objectKey,
    )
      ? language.t(
          'platform.screen.ConstructionPlanCard.module-linked-current-configuration-has-not-been-verified',
          '已关联模块，当前配置尚未核实',
        )
      : language.t(
          'platform.screen.ConstructionPlanCard.no-module-linked-current-configuration-has-not-been-verified',
          '尚未关联模块，当前配置尚未核实',
        );
  if (value.runtimeStatus !== 'ACTIVE')
    return language.t(
      'platform.screen.ConstructionPlanCard.configuration-submitted-awaiting-availability-verification',
      '配置已提交，正在等待可用状态确认',
    );
  if (value.needsReview)
    return language.t(
      'platform.screen.ConstructionPlanCard.configuration-exists-review-the-latest-changes',
      '已有配置，需要核对最新变化',
    );
  if (value.entryVisible && value.pagePublished)
    return language.t(
      'platform.screen.ConstructionPlanCard.page-and-entry-are-available-business-testing-is-pending',
      '页面和入口已可用，待实际试用',
    );
  if (value.pagePublished)
    return language.t(
      'platform.screen.ConstructionPlanCard.page-published-access-entry-is-pending',
      '页面已发布，访问入口待完成',
    );
  if (object.requirements.some((item) => item.status === 'CONFIGURATION_MISSING'))
    return language.t(
      'platform.screen.ConstructionPlanCard.created-complete-registration-fields-and-publish-the-page',
      '已建立，登记内容待补齐，页面尚未发布',
    );
  if (currentTask.value?.unmappedRequirements.length)
    return language.t(
      'platform.screen.ConstructionPlanCard.configuration-exists-review-current-requirements-and-publish-the-page',
      '已有配置，本期要求待核对，页面尚未发布',
    );
  return language.t(
    'platform.screen.ConstructionPlanCard.registration-fields-configured-page-not-yet-published',
    '登记内容已配置，页面尚未发布',
  );
}

const presentation = computed(
  () => state.value.candidate && presentConstructionPlan(state.value.candidate, language),
);
function headerTitle() {
  const title = delivered.value
    ? language.t('platform.screen.ConstructionPlanCard.historical-construction-record', '历史建设记录')
    : language.t('platform.screen.ConstructionPlanCard.current-construction-design', '本次建设设计');
  return state.value.candidate
    ? language.t('platform.screen.useModulePageSession.value-value', '{value1}：{value2}', {
        value1: title,
        value2: state.value.candidate.title,
      })
    : title;
}
async function run(action: () => unknown) {
  error.value = undefined;
  working.value = true;
  try {
    await action();
  } catch (cause) {
    error.value = () =>
      cause instanceof AppError
        ? userFacingErrorMessage(cause, language)
        : cause instanceof Error
          ? cause.message
          : language.t('platform.screen.ConstructionPlanCard.plan-operation-failed', '方案操作失败');
  } finally {
    working.value = false;
  }
}
</script>
<template>
  <section
    v-if="currentTask"
    class="construction-plan"
    :aria-label="
      language.t('platform.screen.ConstructionPlanCard.current-construction-progress', '当前建设进度')
    "
  >
    <strong>{{
      language.t('platform.screen.ConstructionPlanCard.current-construction-progress', '当前建设进度')
    }}</strong>
    <p v-for="object in currentTask.objects" :key="object.objectKey">
      {{
        language.t('platform.screen.useModulePageSession.value-value', '{value1}：{value2}', {
          value1: object.title,
          value2: progressLabel(object),
        })
      }}
      <span v-if="object.options.some((option) => option.action === 'REVIEW_REQUIREMENTS')">
        {{
          language.t(
            'platform.screen.ConstructionPlanCard.some-requirements-need-agreement-before-full-delivery',
            '仍有要求待商定，尚不能完整交付。',
          )
        }}
      </span>
    </p>
    <UiButton size="small" :disabled="disabled" @click="refreshTask">{{
      language.t('platform.screen.ConstructionPlanCard.refresh-actual-progress', '刷新实际进度')
    }}</UiButton>
  </section>
  <p v-if="taskError" role="status">
    {{
      language.t(
        'platform.screen.ConstructionPlanCard.actual-construction-progress-is-unavailable-please-try-again',
        '实际建设进度暂时无法读取，请重试。',
      )
    }}
    <UiButton size="small" :disabled="disabled" @click="refreshTask">{{
      language.t('platform.screen.ConstructionPlanCard.retry-reading-progress', '重试读取进度')
    }}</UiButton>
  </p>
  <details class="construction-plan">
    <summary>
      {{ headerTitle() }}
      <span v-if="state.candidate" class="construction-plan__persistence">
        {{
          delivered
            ? language.t(
                'platform.screen.ConstructionPlanCard.delivered-use-current-configuration-for-improvements',
                '已交付 · 后续改进以当前配置为准',
              )
            : session.recovery.value
              ? language.t(
                  'platform.screen.ConstructionPlanCard.save-result-needs-verification',
                  '保存结果待查询',
                )
              : session.dirty()
                ? language.t(
                    'platform.screen.ConstructionPlanCard.current-changes-are-unsaved',
                    '当前修改未保存',
                  )
                : state.saved
                  ? language.t(
                      'platform.screen.ConstructionPlanCard.requirements-saved-revision-value',
                      '需求已保存 · 第 {value1} 版',
                      { value1: state.saved.revision },
                    )
                  : language.t('platform.screen.ConstructionPlanCard.not-saved-yet', '尚未保存')
        }}
      </span>
    </summary>
    <p v-if="delivered">
      {{
        language.t(
          'platform.screen.ConstructionPlanCard.this-business-has-been-delivered-this-record-retains-the-design',
          '业务已交付。这份记录保留当时的设计与交付结果，不代表当前配置。直接告诉助手要改进什么，无需重新恢复或修改此方案。',
        )
      }}
    </p>
    <p v-else>
      {{
        language.t(
          'platform.screen.ConstructionPlanCard.agree-on-the-goal-and-scope-here-to-improve-an',
          '在这里商定本次建设的目标与范围。改进已有业务可直接读取当前配置，无需另建方案。',
        )
      }}
    </p>
    <p v-if="state.candidate && !delivered">
      {{
        state.saved
          ? language.t(
              'platform.screen.ConstructionPlanCard.revision-value-confirmed',
              '已确认第 {value1} 版',
              { value1: state.saved.revision },
            )
          : language.t('platform.screen.ConstructionPlanCard.not-confirmed-yet', '尚未确认')
      }}
      ·
      {{
        session.dirty()
          ? language.t('platform.screen.ConstructionPlanCard.unconfirmed-changes', '有未确认修改')
          : language.t(
              'platform.screen.ConstructionPlanCard.matches-the-confirmed-revision',
              '与已确认版本一致',
            )
      }}
      ·
      {{
        constructionPlanBindings(state.saved).length
          ? language.t(
              'platform.screen.ConstructionPlanCard.standard-module-linked-query-actual-configuration-and-acceptance-status',
              '已关联标准模块，当前配置与验收状态以实际查询为准',
            )
          : language.t('platform.screen.ConstructionPlanCard.no-standard-module-linked', '尚未关联标准模块')
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
      <summary>
        {{
          language.t(
            'platform.screen.ConstructionPlanCard.view-configuration-submissions',
            '查看配置提交记录',
          )
        }}
      </summary>
      <p v-for="item in state.saved?.initializations ?? []" :key="item.objectKey">
        {{
          language.t(
            'platform.screen.ConstructionPlanCard.initialized-value-based-on-revision-value',
            '已初始化：{value1}（依据第 {value2} 版）',
            { value1: item.moduleAlias, value2: item.planRevision },
          )
        }}{{
          item.planRevision !== state.saved?.revision
            ? language.t(
                'platform.screen.ConstructionPlanCard.requirements-have-changed-review-subsequent-configuration',
                '；需求版本已变化，后续配置须重新核对',
              )
            : ''
        }}
      </p>
      <p v-for="delivery in state.saved?.deliveries ?? []" :key="delivery.requestId">
        {{
          language.t(
            'platform.screen.ConstructionPlanCard.value-value-requirements-revision-value',
            '{value1}：{value2} · 需求第 {value3} 版',
            {
              value1:
                delivery.kind === 'PAGE'
                  ? language.t('platform.screen.ConstructionPlanCard.page-publication', '页面发布')
                  : language.t('platform.screen.ConstructionPlanCard.entry-creation', '入口创建'),
              value2: delivery.moduleAlias,
              value3: delivery.planRevision,
            },
          )
        }}
      </p>
      <p v-for="change in state.saved?.fieldChanges ?? []" :key="change.requestId">
        {{
          language.t(
            'platform.screen.ConstructionPlanCard.fields-submitted-value-based-on-revision-value',
            '字段已提交：{value1}（依据第 {value2} 版）',
            {
              value1: change.fields
                .map((field) => field.title)
                .join(language.t('platform.list.separator', '、')),
              value2: change.planRevision,
            },
          )
        }}{{
          change.planRevision !== state.saved?.revision
            ? language.t(
                'platform.screen.ConstructionPlanCard.requirements-revised-review-existing-fields',
                '；需求已修订，须核对已有字段',
              )
            : ''
        }}{{
          language.t(
            'platform.screen.ConstructionPlanCard.query-actual-construction-progress-for-page-and-entry-status',
            '；页面和入口状态请查询实际建设进度。',
          )
        }}
      </p>
    </details>
    <p v-if="state.reviewRequired" role="status">
      {{
        language.t(
          'platform.screen.ConstructionPlanCard.the-goal-or-scope-changed-ask-the-assistant-to-review',
          '目标或范围已修改。原有问题、假设和规则待重新核对，请告诉助手“核对修改后的方案”再确认；不会自动视为已解决。',
        )
      }}
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
      >{{
        language.t(
          'platform.screen.ConstructionPlanCard.query-actual-construction-progress',
          '查询实际建设进度',
        )
      }}</UiButton
    >
    <section
      v-for="item in progress"
      :key="item.objectKey"
      :aria-label="
        language.t('platform.screen.ConstructionPlanCard.actual-construction-progress', '实际建设进度')
      "
    >
      <p>
        {{
          language.t(
            'platform.screen.ConstructionPlanCard.value-page-value-entry-value-runtime-value',
            '{value1}：页面{value2}，入口{value3}，运行态 {value4}',
            {
              value1: item.moduleAlias,
              value2: item.pagePublished
                ? language.t('platform.workflow.published', '已发布')
                : language.t('platform.screen.RecordQueryListPanel.incomplete', '未完成'),
              value3: item.entryVisible
                ? language.t('platform.screen.ConstructionPlanCard.visible', '可见')
                : language.t('platform.screen.ConstructionPlanCard.not-visible', '不可见'),
              value4: item.runtimeStatus,
            },
          )
        }}
      </p>
      <p>
        {{
          language.t('platform.screen.ConstructionPlanCard.human-acceptance-value', '人工验收：{value1}', {
            value1: item.acceptanceConfirmed
              ? language.t(
                  'platform.screen.ConstructionPlanCard.current-baseline-accepted',
                  '当前基线已确认通过',
                )
              : language.t('platform.screen.ConstructionPlanCard.awaiting-verification', '待核对'),
          })
        }}
      </p>
      <p v-for="line in item.remainingWork" :key="line">{{ line }}</p>
    </section>
    <UiButton
      v-if="state.saved && !delivered && session.facts().governanceAvailable"
      :disabled="disabled || working || session.dirty()"
      @click="run(() => session.readTask())"
      >{{ language.t('platform.screen.ConstructionPlanCard.view-next-steps', '查看下一步') }}</UiButton
    >
    <details v-if="currentTask?.unmappedRequirements.length">
      <summary>
        {{
          language.t(
            'platform.screen.ConstructionPlanCard.requirements-without-a-business-object',
            '尚未对应业务对象的要求',
          )
        }}
      </summary>
      <p v-for="item in currentTask.unmappedRequirements" :key="`${item.section}:${item.index}`">
        {{
          language.t(
            'platform.screen.ConstructionPlanCard.value-unmapped-value',
            '{value1}：尚未对应。{value2}',
            { value1: item.statement, value2: item.explanation },
          )
        }}
      </p>
    </details>
    <section
      v-for="object in session.currentTask()?.objects ?? []"
      :key="object.objectKey"
      :aria-label="language.t('platform.screen.ConstructionPlanCard.construction-task', '建设任务')"
    >
      <p>
        {{
          language.t('platform.screen.useModulePageSession.value-value', '{value1}：{value2}', {
            value1: object.title,
            value2: object.complete
              ? language.t(
                  'platform.screen.ConstructionPlanCard.delivered-use-current-governance-configuration-for-subsequent-work',
                  '已交付，后续以当前治理配置为准',
                )
              : language.t('platform.screen.ConstructionPlanCard.work-that-can-continue', '可继续处理的事项'),
          })
        }}
      </p>
      <ul v-if="!object.complete">
        <li v-for="option in object.options" :key="option.action">{{ option.explanation }}</li>
      </ul>
      <details>
        <summary>
          {{
            language.t(
              'platform.screen.ConstructionPlanCard.review-each-current-requirement',
              '逐项核对本期要求',
            )
          }}
        </summary>
        <p v-for="(item, index) in object.requirements" :key="index">
          {{
            language.t(
              'platform.screen.ConstructionPlanCard.value-value-value',
              '{value1}：{value2}。{value3}',
              {
                value1: item.statement,
                value2: {
                  UNMAPPED: language.t('platform.screen.ConstructionPlanCard.unmapped', '尚未对应'),
                  UNSUPPORTED: language.t('platform.screen.ConstructionPlanCard.unsupported', '暂不支持'),
                  CONFIGURATION_MISSING: language.t(
                    'platform.screen.ConstructionPlanCard.required-configuration-is-not-ready',
                    '所需配置尚未就绪',
                  ),
                  CONFIGURATION_MATCHED: language.t(
                    'platform.screen.ConstructionPlanCard.configuration-verified-business-testing-is-still-required',
                    '配置已核对，仍须业务试用',
                  ),
                  MANUAL_RESPONSIBILITY: language.t(
                    'platform.screen.ConstructionPlanCard.handled-by-people-as-agreed',
                    '按约定由人处理',
                  ),
                }[item.status],
                value3: item.explanation,
              },
            )
          }}
        </p>
      </details>
    </section>
    <p v-if="session.dirty()">
      {{
        language.t(
          'platform.screen.ConstructionPlanCard.changes-in-this-revision-value',
          '本版变化：{value1}',
          { value1: session.changes().join(language.t('platform.list.separator', '、')) },
        )
      }}
    </p>
    <details v-if="presentation">
      <summary>
        {{
          delivered
            ? language.t(
                'platform.screen.ConstructionPlanCard.view-the-design-at-that-time',
                '查看当时的设计',
              )
            : language.t('platform.screen.ConstructionPlanCard.review-current-candidate', '审阅当前候选')
        }}
      </summary>
      <p>{{ state.candidate?.goal }}</p>
      <details v-for="section in presentation.sections" :key="section.title" :open="section.expanded">
        <summary>
          {{
            language.t('platform.screen.ConstructionPlanCard.value-value-items', '{value1} · {value2} 项', {
              value1: section.title,
              value2: section.lines.length,
            })
          }}
        </summary>
        <ul>
          <li v-for="(line, index) in section.lines" :key="index">{{ line }}</li>
        </ul>
      </details>
      <p v-if="!delivered">
        {{
          language.t(
            'platform.screen.ConstructionPlanCard.this-confirmation-saves-the-requirements-scope-business-configuration-is-not',
            '本次仅确认需求范围，不创建或发布业务配置；已有建设结果保留。',
          )
        }}
      </p>
    </details>
    <UiButton
      v-if="state.candidate && !editing && !delivered"
      :disabled="disabled || working"
      @click="run(beginEdit)"
      >{{ language.t('platform.screen.ConstructionPlanCard.edit-business-plan', '修改业务方案') }}</UiButton
    >
    <section
      v-if="editing"
      :aria-label="language.t('platform.screen.ConstructionPlanCard.edit-plan-candidate', '修改方案候选')"
    >
      <label
        >{{ language.t('platform.screen.ConstructionPlanCard.scheme-name', '方案名称')
        }}<input v-model="editTitle" maxlength="120" :disabled="disabled || working"
      /></label>
      <label
        >{{ language.t('platform.screen.ConstructionPlanCard.business-goal', '业务目标')
        }}<textarea v-model="editGoal" maxlength="1500" :disabled="disabled || working" />
      </label>
      <label v-for="(label, key) in sections" :key="key"
        >{{
          language.t('platform.screen.ConstructionPlanCard.value-one-item-per-line', '{value1}（每行一项）', {
            value1: label,
          })
        }}<textarea v-model="editSections[key]" :disabled="disabled || working" />
      </label>
      <UiButton :disabled="disabled || working" @click="run(applyEdit)">{{
        language.t(
          'platform.screen.ConstructionPlanCard.update-candidate-for-later-confirmation',
          '更新候选，稍后确认',
        )
      }}</UiButton>
      <UiButton :disabled="disabled || working" @click="session.cancelManualEdit()">{{
        language.t('platform.screen.ConstructionPlanCard.cancel-manual-editing', '取消人工修改')
      }}</UiButton>
    </section>
    <details v-if="state.saved">
      <summary>
        {{ language.t('platform.screen.ConstructionPlanCard.confirmed-history', '已确认历史') }}
      </summary>
      <UiButton
        :disabled="disabled || working"
        @click="
          run(async () => {
            revisions = await session.history();
          })
        "
        >{{
          language.t('platform.screen.ConstructionPlanCard.read-historical-revisions', '读取历史版本')
        }}</UiButton
      >
      <details v-for="revision in revisions" :key="revision.revision">
        <summary>
          {{
            language.t(
              'platform.screen.ConstructionPlanCard.revision-value-value',
              '第 {value1} 版 · {value2}',
              { value1: revision.revision, value2: revision.content.title },
            )
          }}
        </summary>
        <p v-for="(line, index) in presentConstructionPlan(revision.content, language).lines" :key="index">
          {{ line }}
        </p>
      </details>
    </details>
    <template v-if="!state.planId">
      <UiButton :disabled="disabled || working" @click="run(() => session.listSaved())">{{
        language.t('platform.screen.ConstructionPlanCard.read-saved-plans', '读取已保存方案')
      }}</UiButton>
      <select
        v-model="selected"
        :aria-label="
          language.t('platform.screen.ConstructionPlanCard.saved-construction-plans', '已保存的建设方案')
        "
        :disabled="disabled || working"
      >
        <option value="">
          {{ language.t('platform.screen.ConstructionPlanCard.select-a-plan', '选择方案') }}
        </option>
        <option v-for="plan in session.savedPlans.value" :key="plan.planId" :value="plan.planId">
          {{
            language.t(
              'platform.screen.ConstructionPlanCard.value-revision-value',
              '{value1} · 第 {value2} 版',
              { value1: plan.title, value2: plan.revision },
            )
          }}
        </option>
      </select>
      <UiButton
        :disabled="disabled || working || !selected || session.dirty()"
        @click="run(() => session.restore(selected))"
        >{{ language.t('platform.screen.ConstructionPlanCard.resume-discussion', '恢复讨论') }}</UiButton
      >
    </template>
    <p v-if="session.recovery.value">
      {{
        language.t(
          'platform.screen.ConstructionPlanCard.the-result-is-uncertain-query-the-confirmation-result-before-resubmitting',
          '结果未确定，请先查询确认结果；在查明前不能重新提交或修改本次方案。',
        )
      }}
    </p>
    <UiButton
      v-if="session.recovery.value"
      :disabled="disabled || working"
      @click="
        run(async () => {
          const result = await session.recovery.value?.();
          if (!result)
            throw new Error(
              language.t(
                'platform.screen.ConstructionPlanCard.the-confirmation-result-is-not-available-yet-query-again-later',
                '尚未查到确认结果，请稍后再次查询',
              ),
            );
        })
      "
      >{{
        language.t(
          'platform.screen.ConstructionPlanCard.query-the-last-confirmation-result',
          '查询上次确认结果',
        )
      }}</UiButton
    >
    <details v-if="session.dirty() && !delivered">
      <summary>
        {{ language.t('platform.screen.ConstructionPlanCard.discard-unconfirmed-changes', '放弃未确认修改') }}
      </summary>
      <p>
        {{
          language.t(
            'platform.screen.ConstructionPlanCard.confirmed-historical-revisions-are-retained-unconfirmed-changes-will-be-discarded',
            '已确认的历史版本仍然保留，未确认修改将被丢弃。',
          )
        }}
      </p>
      <UiButton :disabled="disabled || working" @click="run(() => session.discardCandidate())">{{
        language.t(
          'platform.screen.ConstructionPlanCard.discard-these-unconfirmed-changes',
          '放弃本次未确认修改',
        )
      }}</UiButton>
    </details>
    <p v-if="state.planId && !delivered">
      {{
        language.t(
          'platform.screen.ConstructionPlanCard.this-conversation-concerns-one-construction-goal-start-a-new-conversation',
          '此对话围绕同一个建设目标；讨论其他独立业务请新建对话。',
        )
      }}
    </p>
    <p v-if="error" role="alert">{{ error?.() }}</p>
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
