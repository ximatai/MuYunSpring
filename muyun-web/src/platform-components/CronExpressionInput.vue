<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { UiButton, UiInput, UiInputGroup, UiPopover, UiSelect } from '@muyun/vue-ui-antdv';
import { useLanguageContext } from '@muyun/web-core';
import {
  cronMinuteIntervals,
  cronWeekdays,
  defaultCronPreset,
  generateCronExpression,
  normalizeCronExpression,
  parseCronPreset,
  type CronExpressionInputProps,
  type CronInputValidity,
  type CronPresetKind,
} from './cronExpression';

defineOptions({ name: 'CronExpressionInput' });
const props = withDefaults(defineProps<CronExpressionInputProps>(), { showFeedback: true });
const emit = defineEmits<{
  'update:value': [value: string];
  'validity-change': [validity: CronInputValidity];
}>();
const language = useLanguageContext();
const t = (key: string, fallback: string) => language.t(`platform.cron.${key}`, fallback);
const draft = ref(props.value ?? '');
const validity = ref<CronInputValidity>({ valid: true });
const checked = ref(false);
const open = ref(false);
const helper = ref(defaultCronPreset());
const custom = ref(false);
const helperError = ref('');
const applying = ref(false);
let sequence = 0;
let applySequence = 0;
let timer: ReturnType<typeof setTimeout> | undefined;
let initialized = false;
let lastDisabled: boolean | undefined;
const invalid = computed(
  () => !props.disabled && (props.invalid || (!validity.value.valid && !validity.value.pending)),
);
const candidate = computed(() => generateCronExpression(helper.value));
const kinds = computed(() => [
  { value: 'daily', label: t('daily', '每天') },
  { value: 'weekly', label: t('weekly', '每周') },
  { value: 'monthly', label: t('monthly', '每月') },
  { value: 'minutes', label: t('minutes', '每隔若干分钟') },
]);
const hours = Array.from({ length: 24 }, (_, value) => ({ value, label: String(value).padStart(2, '0') }));
const minutes = Array.from({ length: 60 }, (_, value) => ({ value, label: String(value).padStart(2, '0') }));
const dates = Array.from({ length: 31 }, (_, index) => ({ value: index + 1, label: String(index + 1) }));
const intervals = cronMinuteIntervals.map((value) => ({ value, label: String(value) }));
const weekdays = computed(() =>
  cronWeekdays.map((value, index) => ({
    value,
    label: t(`weekday-${value}`, ['周一', '周二', '周三', '周四', '周五', '周六', '周日'][index]!),
  })),
);

function report(next: CronInputValidity) {
  validity.value = next;
  emit('validity-change', next);
}
function cancelTimer() {
  if (timer !== undefined) clearTimeout(timer);
  timer = undefined;
}
function validationMessage(reason?: string | null) {
  return reason === 'MINUTE_PRECISION_REQUIRED'
    ? t('precision', '平台调度精度为分钟，请将秒位固定为 0。')
    : t('invalid', 'Cron 表达式无效，请检查六个字段；也可使用计划助手生成。');
}
function edit(value: string) {
  if (value === draft.value) return;
  ++sequence;
  ++applySequence;
  applying.value = false;
  cancelTimer();
  checked.value = false;
  draft.value = value;
  emit('update:value', value);
  if (!value.trim()) {
    report({ valid: true });
    return;
  }
  report({ valid: false, pending: true });
  // Debouncing also allows Save to become available while the input remains focused.
  timer = setTimeout(() => void validateCurrent(), 400);
}
async function validateCurrent(normalize = true) {
  cancelTimer();
  const revision = ++sequence;
  if (props.disabled || !draft.value.trim()) {
    report({ valid: true });
    return;
  }
  const expression = normalize ? normalizeCronExpression(draft.value) : draft.value;
  report({ valid: false, pending: true });
  try {
    const result = await props.validator(expression);
    if (revision !== sequence) return;
    if (result.valid) {
      checked.value = true;
      report({ valid: true });
      if (draft.value !== expression) {
        draft.value = expression;
        emit('update:value', expression);
      }
    } else {
      checked.value = false;
      report({ valid: false, message: validationMessage(result.reason) });
    }
  } catch {
    if (revision === sequence)
      report({ valid: false, message: t('unavailable', '校验服务暂不可用，请重新校验。') });
  }
}
async function apply() {
  const expression = candidate.value;
  if (!expression) return;
  const revision = ++applySequence;
  applying.value = true;
  helperError.value = '';
  try {
    const result = await props.validator(expression);
    if (revision !== applySequence || !open.value || expression !== candidate.value) return;
    if (!result.valid) {
      helperError.value = validationMessage(result.reason);
      return;
    }
    ++sequence;
    cancelTimer();
    checked.value = true;
    report({ valid: true });
    if (expression !== draft.value) {
      draft.value = expression;
      emit('update:value', expression);
    }
    open.value = false;
  } catch {
    if (revision === applySequence) helperError.value = t('unavailable', '校验服务暂不可用，请重新校验。');
  } finally {
    if (revision === applySequence) applying.value = false;
  }
}
function setKind(value: unknown) {
  if (kinds.value.some((kind) => kind.value === value)) helper.value.kind = value as CronPresetKind;
}
function setWeekdays(value: unknown) {
  helper.value.weekdays = Array.isArray(value)
    ? value.filter((day) => cronWeekdays.some((known) => known === day))
    : [];
}
watch(open, (value) => {
  ++applySequence;
  applying.value = false;
  helperError.value = '';
  if (value) {
    const parsed = parseCronPreset(draft.value);
    helper.value = parsed ?? defaultCronPreset();
    custom.value = !parsed && Boolean(draft.value.trim());
  }
});
watch(
  [() => props.value, () => props.disabled],
  ([value, disabled]) => {
    const next = value ?? '';
    if (initialized && disabled === lastDisabled && next === draft.value) return;
    initialized = true;
    lastDisabled = disabled;
    ++sequence;
    ++applySequence;
    cancelTimer();
    applying.value = false;
    checked.value = false;
    draft.value = next;
    if (disabled) {
      open.value = false;
      report({ valid: true });
    } else void validateCurrent(false);
  },
  { immediate: true },
);
onBeforeUnmount(() => {
  ++sequence;
  ++applySequence;
  cancelTimer();
});
</script>

<template>
  <div class="cron-expression-input" :aria-busy="validity.pending || undefined">
    <UiInputGroup
      :disabled="disabled"
      :invalid="invalid"
      :aria-label="ariaLabel ?? t('expression', 'Cron 表达式')"
    >
      <UiInput
        class="cron-expression-input__expression"
        :value="draft"
        :disabled="disabled"
        :invalid="invalid"
        :aria-label="ariaLabel ?? t('expression', 'Cron 表达式')"
        @update:value="edit"
        @blur="validateCurrent()"
      />
      <template #addon>
        <UiPopover v-model:open="open" placement="bottomRight">
          <UiButton
            type="text"
            :disabled="disabled"
            :aria-expanded="open"
            :aria-label="t('assistant', '计划助手')"
          >
            {{ t('assistant', '计划助手') }}
          </UiButton>
          <template #content>
            <section class="cron-assistant" :aria-label="t('assistant', '计划助手')">
              <h3>{{ t('common', '常用计划') }}</h3>
              <p v-if="custom" class="cron-assistant__hint">
                {{ t('custom', '当前为自定义表达式。助手只在点击“应用计划”后替换原值。') }}
              </p>
              <label
                >{{ t('period', '执行周期') }}
                <UiSelect
                  :value="helper.kind"
                  :options="kinds"
                  :allow-clear="false"
                  :aria-label="t('period', '执行周期')"
                  @update:value="setKind"
                />
              </label>
              <label v-if="helper.kind === 'minutes'"
                >{{ t('interval', '间隔（分钟）') }}
                <UiSelect
                  :value="helper.interval"
                  :options="intervals"
                  :allow-clear="false"
                  :aria-label="t('interval', '间隔（分钟）')"
                  @update:value="helper.interval = Number($event)"
                />
              </label>
              <label v-if="helper.kind === 'weekly'"
                >{{ t('weekdays', '星期') }}
                <UiSelect
                  :value="helper.weekdays"
                  :options="weekdays"
                  mode="multiple"
                  :aria-label="t('weekdays', '星期')"
                  @update:value="setWeekdays"
                />
              </label>
              <label v-if="helper.kind === 'monthly'"
                >{{ t('date', '日期') }}
                <UiSelect
                  :value="helper.day"
                  :options="dates"
                  :allow-clear="false"
                  :aria-label="t('date', '日期')"
                  @update:value="helper.day = Number($event)"
                />
              </label>
              <div v-if="helper.kind !== 'minutes'" class="cron-assistant__time">
                <label
                  >{{ t('hour', '小时')
                  }}<UiSelect
                    :value="helper.hour"
                    :options="hours"
                    :allow-clear="false"
                    :aria-label="t('hour', '小时')"
                    @update:value="helper.hour = Number($event)"
                /></label>
                <label
                  >{{ t('minute', '分钟')
                  }}<UiSelect
                    :value="helper.minute"
                    :options="minutes"
                    :allow-clear="false"
                    :aria-label="t('minute', '分钟')"
                    @update:value="helper.minute = Number($event)"
                /></label>
              </div>
              <p v-if="helper.kind === 'monthly'" class="cron-assistant__hint">
                {{ t('short-month', '当月没有所选日期时，该月跳过执行。') }}
              </p>
              <p v-if="helper.kind === 'minutes'" class="cron-assistant__hint">
                {{ t('aligned', '按每小时的分钟刻度执行，不是从保存时刻开始计时。') }}
              </p>
              <p class="cron-assistant__hint">
                {{ t('timezone', '执行时间以表单所选时区为准；秒位固定为 0。') }}
              </p>
              <code class="cron-assistant__preview">{{
                candidate ?? t('choose', '请选择有效的周期选项')
              }}</code>
              <p v-if="helperError" role="alert" class="cron-assistant__error">{{ helperError }}</p>
              <footer>
                <UiButton @click="open = false"
                  ><span>{{ t('cancel', '取消') }}</span></UiButton
                >
                <UiButton type="primary" :disabled="!candidate" :loading="applying" @click="apply">
                  <span>{{ t('apply', '应用计划') }}</span>
                </UiButton>
              </footer>
            </section>
          </template>
        </UiPopover>
      </template>
    </UiInputGroup>
    <p
      v-if="showFeedback && (validity.pending || validity.message || checked)"
      class="cron-expression-input__feedback"
      :class="{ 'cron-assistant__error': validity.message }"
      :role="validity.message ? 'alert' : 'status'"
    >
      {{
        validity.pending
          ? t('checking', '正在校验…')
          : (validity.message ?? t('checked', '格式已校验（分钟精度）'))
      }}
      <UiButton v-if="validity.message" type="link" size="small" @click="validateCurrent()">
        <span>{{ t('retry', '重新校验') }}</span>
      </UiButton>
    </p>
  </div>
</template>

<style scoped>
.cron-expression-input {
  min-width: 0;
}
.cron-expression-input__expression {
  font-family: var(--muyun-font-family-monospace, ui-monospace, monospace);
}
.cron-expression-input__feedback {
  margin: 4px 0 0;
  color: var(--muyun-text-muted);
  font-size: 12px;
}
.cron-assistant {
  display: grid;
  gap: 12px;
  width: min(320px, calc(100vw - 64px));
  color: var(--muyun-text);
}
.cron-assistant h3,
.cron-assistant p {
  margin: 0;
}
.cron-assistant h3 {
  font-size: 14px;
}
.cron-assistant label {
  display: grid;
  gap: 6px;
  min-width: 0;
  font-size: 13px;
}
.cron-assistant__time {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}
.cron-assistant__hint {
  color: var(--muyun-text-muted);
  font-size: 12px;
  line-height: 1.6;
}
.cron-assistant__preview {
  padding: 8px 10px;
  border-radius: 4px;
  background: var(--muyun-hover);
  overflow-wrap: anywhere;
}
.cron-assistant__error {
  color: var(--muyun-danger-text);
}
.cron-assistant footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
