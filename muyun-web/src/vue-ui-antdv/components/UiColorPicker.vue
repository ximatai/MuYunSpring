<script setup lang="ts">
import { Input as AInput, Popover as APopover } from 'ant-design-vue';
import { ref, watch } from 'vue';

defineOptions({ name: 'UiColorPicker', inheritAttrs: false });

const props = withDefaults(
  defineProps<{
    value?: string;
    disabled?: boolean;
  }>(),
  {
    value: '#1677FF',
    disabled: false,
  },
);

const emit = defineEmits<{
  'update:value': [value: string];
}>();

const palette = [
  '#1677FF',
  '#13C2C2',
  '#52C41A',
  '#A0D911',
  '#FAAD14',
  '#FA8C16',
  '#F5222D',
  '#EB2F96',
  '#722ED1',
  '#2F54EB',
  '#8C8C8C',
  '#434343',
];
const draft = ref(props.value);

watch(
  () => props.value,
  (value) => {
    draft.value = value;
  },
);

function updateValue(value: string) {
  if (props.disabled) {
    return;
  }
  const normalized = value.trim().toUpperCase();
  if (/^#[0-9A-F]{6}$/.test(normalized)) {
    draft.value = normalized;
    emit('update:value', normalized);
  }
}

function updateInputValue(event: Event) {
  draft.value = (event.target as HTMLInputElement).value;
}

function updateNativeColor(event: Event) {
  updateValue((event.target as HTMLInputElement).value);
}

function commitDraft() {
  if (/^#[0-9A-Fa-f]{6}$/.test(draft.value.trim())) {
    updateValue(draft.value);
    return;
  }
  draft.value = props.value;
}
</script>

<template>
  <div class="ui-color-picker" :class="$attrs.class" :style="$attrs.style">
    <APopover :trigger="props.disabled ? [] : 'click'" placement="bottomLeft">
      <template #content>
        <div class="ui-color-picker-panel">
          <span class="ui-color-picker-caption">推荐颜色</span>
          <div class="ui-color-picker-palette">
            <button
              v-for="color in palette"
              :key="color"
              type="button"
              class="ui-color-picker-swatch"
              :class="{ selected: color === props.value }"
              :style="{ backgroundColor: color }"
              :aria-label="`选择 ${color}`"
              :disabled="props.disabled"
              @click="updateValue(color)"
            />
          </div>
          <label class="ui-color-picker-native">
            <span>自定义</span>
            <input type="color" :value="props.value" :disabled="props.disabled" @input="updateNativeColor" />
          </label>
        </div>
      </template>
      <AInput
        class="ui-color-picker-input"
        :value="draft"
        :disabled="props.disabled"
        :maxlength="7"
        @input="updateInputValue"
        @blur="commitDraft"
        @press-enter="commitDraft"
      >
        <template #prefix>
          <i class="ui-color-picker-dot" :style="{ backgroundColor: props.value }" aria-hidden="true" />
        </template>
      </AInput>
    </APopover>
  </div>
</template>

<style scoped>
.ui-color-picker {
  width: 100%;
}
.ui-color-picker-input {
  width: 132px;
}
.ui-color-picker-dot {
  display: inline-block;
  width: 14px;
  height: 14px;
  border: 1px solid rgb(15 23 42 / 18%);
  border-radius: 50%;
}
.ui-color-picker-panel {
  display: grid;
  gap: 8px;
  min-width: 172px;
}
.ui-color-picker-caption {
  color: #64748b;
  font-size: 12px;
}
.ui-color-picker-palette {
  display: grid;
  grid-template-columns: repeat(6, 22px);
  gap: 8px;
}
.ui-color-picker-swatch {
  width: 22px;
  height: 22px;
  border: 1px solid rgb(15 23 42 / 18%);
  border-radius: 50%;
  cursor: pointer;
}
.ui-color-picker-swatch.selected {
  outline: 2px solid var(--muyun-theme-base, #0052d9);
  outline-offset: 2px;
}
.ui-color-picker-native {
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: #475569;
  font-size: 12px;
}
.ui-color-picker-native input {
  width: 34px;
  height: 24px;
  padding: 0;
  border: 0;
  background: transparent;
}
</style>
