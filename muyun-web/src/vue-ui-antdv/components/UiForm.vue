<script setup lang="ts">
import { useLanguageContext } from '@muyun/web-core';
import { computed } from 'vue';
import { Form as AForm, FormItem as AFormItem } from 'ant-design-vue';
import { resolveFieldComponent } from '../registry';
import UiButton from './UiButton.vue';
import type {
  FieldCondition,
  FieldContract,
  FormContract,
  Primitive,
  RecordData,
} from '@muyun/web-contracts';

const language = useLanguageContext();

defineOptions({ name: 'UiForm', inheritAttrs: false });

const props = defineProps<{
  contract: FormContract;
  modelValue: RecordData;
  submitText?: string;
}>();

const emit = defineEmits<{
  'update:modelValue': [value: RecordData];
  submit: [value: RecordData];
}>();

const formModel = computed({
  get: () => props.modelValue,
  set: (value: RecordData) => emit('update:modelValue', value),
});

function matchCondition(condition: FieldCondition | undefined) {
  if (!condition) {
    return false;
  }

  const actual = props.modelValue[condition.field];
  if ('equals' in condition) {
    return actual === condition.equals;
  }
  if ('notEquals' in condition) {
    return actual !== condition.notEquals;
  }
  return false;
}

function isVisible(field: FieldContract) {
  return field.visibleWhen ? matchCondition(field.visibleWhen) : true;
}

function isReadonly(field: FieldContract) {
  return Boolean(field.disabled || matchCondition(field.readonlyWhen));
}

function isRequired(field: FieldContract) {
  return Boolean(field.required || matchCondition(field.requiredWhen));
}

function updateField(name: string, value: Primitive) {
  emit('update:modelValue', {
    ...props.modelValue,
    [name]: value,
  });
}

function mergePatch(patch: Record<string, Primitive>) {
  emit('update:modelValue', {
    ...props.modelValue,
    ...patch,
  });
}

const visibleFields = computed(() => props.contract.fields.filter(isVisible));
</script>

<template>
  <AForm
    :model="formModel"
    layout="vertical"
    :class="$attrs.class"
    :style="$attrs.style"
    @finish="emit('submit', modelValue)"
  >
    <AFormItem
      v-for="field in visibleFields"
      :key="field.name"
      :label="field.label"
      :name="field.name"
      :rules="
        isRequired(field)
          ? [
              {
                required: true,
                message: language.t('platform.screen.UiForm.enter-value', '请填写{value1}', {
                  value1: field.label,
                }),
              },
            ]
          : undefined
      "
    >
      <component
        :is="resolveFieldComponent(field.kind)"
        :value="modelValue[field.name]"
        :placeholder="field.placeholder"
        :disabled="isReadonly(field)"
        :options="field.options ?? []"
        :dictionary-alias="field.dictionaryAlias"
        :reference="field.reference"
        @update:value="updateField(field.name, $event)"
        @fill-back="mergePatch"
      />
    </AFormItem>

    <AFormItem>
      <UiButton type="primary" html-type="submit">{{
        submitText ?? language.t('platform.action.save', '保存')
      }}</UiButton>
    </AFormItem>
  </AForm>
</template>
