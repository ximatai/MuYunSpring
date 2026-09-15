<script setup lang="ts">
import { computed, getCurrentInstance } from 'vue';
import ReferencePicker from './ReferencePicker.vue';
import {
  createUserReferencePickerProvider,
  toUserPickerCandidate,
  userReferencePickerColumns,
} from './userReferencePicker';
import type {
  ReferencePickerCandidate,
  ReferencePickerSourceIdentity,
  ReferencePickerValidity,
} from './referencePickerModel';
import type {
  UserAccountId,
  UserPickerCandidate,
  UserPickerPageSearch,
  UserPickerResolver,
} from './userPickerModel';

defineOptions({ name: 'UserPicker' });

const props = withDefaults(
  defineProps<{
    value?: UserAccountId | readonly UserAccountId[];
    multiple?: boolean;
    maxSelection?: number;
    placeholder?: string;
    disabled?: boolean;
    allowClear?: boolean;
    pageSize?: number;
    title?: string;
    searchPlaceholder?: string;
    emptyDescription?: string;
    selectionNoun?: string;
    reloadKey?: string | number;
    /** A caller declares its source purpose; account target identity alone is never a cache scope. */
    sourceIdentity?: ReferencePickerSourceIdentity;
    searchPage: UserPickerPageSearch;
    resolveUsers: UserPickerResolver;
  }>(),
  {
    value: undefined,
    multiple: false,
    maxSelection: undefined,
    placeholder: '搜索并选择用户',
    disabled: false,
    allowClear: true,
    pageSize: 20,
    title: '选择用户',
    searchPlaceholder: '按账号或用户 ID 搜索',
    emptyDescription: '没有可选择的用户',
    selectionNoun: '用户',
    reloadKey: undefined,
    sourceIdentity: undefined,
  },
);

const emit = defineEmits<{
  'update:value': [value: UserAccountId | UserAccountId[] | undefined];
  select: [users: UserPickerCandidate[]];
  'selection-resolved': [users: UserPickerCandidate[]];
  'validity-change': [validity: ReferencePickerValidity];
}>();

const instanceId = getCurrentInstance()?.uid ?? Math.random().toString(36).slice(2);
const sourceIdentity = computed<ReferencePickerSourceIdentity>(
  () =>
    props.sourceIdentity ?? {
      targetModuleAlias: 'iam.user',
      // This default only isolates a legacy component instance; real shared sources must declare identity.
      source: { kind: 'targetReference', id: `legacy-user-picker-${instanceId}` },
    },
);
const provider = computed(() =>
  createUserReferencePickerProvider({
    sourceIdentity: sourceIdentity.value,
    searchPage: props.searchPage,
    resolveUsers: props.resolveUsers,
  }),
);

function updateValue(value: string | string[] | undefined) {
  emit('update:value', value);
}

function select(candidates: ReferencePickerCandidate[]) {
  emit('select', candidates.map(toUserPickerCandidate));
}

function resolved(candidates: ReferencePickerCandidate[]) {
  emit('selection-resolved', candidates.map(toUserPickerCandidate));
}

function validityChanged(validity: ReferencePickerValidity) {
  emit('validity-change', validity);
}
</script>

<template>
  <ReferencePicker
    :value="value"
    :multiple="multiple"
    :max-selection="maxSelection"
    :provider="provider"
    :reload-key="reloadKey"
    :columns="userReferencePickerColumns"
    :placeholder="placeholder"
    :disabled="disabled"
    :allow-clear="allowClear"
    :page-size="pageSize"
    :title="title"
    :search-placeholder="searchPlaceholder"
    :empty-description="emptyDescription"
    :selection-noun="selectionNoun"
    @update:value="updateValue"
    @select="select"
    @selection-resolved="resolved"
    @validity-change="validityChanged"
  />
</template>
