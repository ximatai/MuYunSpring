<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import {
  RecordContentSectionHeading,
  RecordDetailPanel,
  handlePlatformActionSuccess,
  presentPlatformError,
} from '@muyun/platform-components';
import { useWorkspaceViewUnsavedState } from '@muyun/platform-workbench';
import { useModuleContext } from '@muyun/web-core';
import { UiActionButton, UiCheckbox, UiEmpty, UiSpin } from '@muyun/vue-ui-antdv';

defineOptions({ name: 'ModuleExperienceProfileOverview' });

const props = defineProps<{ moduleAlias: string; moduleTitle?: string }>();
type ExperienceMode = 'TREE_CARD' | 'LIST_CARD' | 'MICRO_LIST_CARD';
type CapabilityGroup = 'required' | 'recommended' | 'optional';
type CapabilityFact = { code: string; title: string; description?: string };
type ExperienceProfile = {
  mode: ExperienceMode;
  version?: number;
  mainMetadataVersion?: number;
  mainCapabilities: string[];
  capabilities?: Partial<Record<CapabilityGroup, CapabilityFact[]>>;
};

const moduleContext = useModuleContext({ moduleAlias: 'platform.module' });
const loading = ref(false);
const saving = ref(false);
const editing = ref(false);
const profile = ref<ExperienceProfile>();
const selectedMode = ref<ExperienceMode>('LIST_CARD');
const selectedCapabilities = ref<string[]>([]);
const modes: Array<{ mode: ExperienceMode; title: string; description: string }> = [
  {
    mode: 'TREE_CARD',
    title: '树 + 卡片',
    description: '适合存在明确层级、需要在树中定位和调整顺序的业务对象。',
  },
  {
    mode: 'LIST_CARD',
    title: '列表 + 卡片',
    description: '适合平铺记录管理，以列表筛选、右侧详情处理日常操作。',
  },
  {
    mode: 'MICRO_LIST_CARD',
    title: '微列表 + 卡片',
    description: '适合记录量较少、以快速选择和轻量维护为主的配置对象。',
  },
];
const fallbackCapabilities: Record<ExperienceMode, Record<CapabilityGroup, CapabilityFact[]>> = {
  TREE_CARD: {
    required: [
      { code: 'TREE', title: '树结构', description: '按父子层级组织记录，便于定位和管理。' },
      { code: 'SORT', title: '排序', description: '按同级顺序展示记录，支持拖拽调整。' },
    ],
    recommended: [
      {
        code: 'DATA_SCOPE',
        title: '数据权限',
        description: '适用于需要按组织、角色或负责人控制数据可见范围的业务。',
      },
    ],
    optional: [{ code: 'ENABLE', title: '启停', description: '允许按记录控制可用状态。' }],
  },
  LIST_CARD: {
    required: [],
    recommended: [
      {
        code: 'DATA_SCOPE',
        title: '数据权限',
        description: '适用于需要按组织、角色或负责人控制数据可见范围的业务。',
      },
    ],
    optional: [
      { code: 'SORT', title: '排序', description: '允许维护列表展示顺序。' },
      { code: 'ENABLE', title: '启停', description: '允许按记录控制可用状态。' },
    ],
  },
  MICRO_LIST_CARD: {
    required: [{ code: 'SORT', title: '排序', description: '微列表按稳定顺序呈现，支持轻量调整展示顺序。' }],
    recommended: [
      {
        code: 'DATA_SCOPE',
        title: '数据权限',
        description: '适用于需要按组织、角色或负责人控制数据可见范围的业务。',
      },
    ],
    optional: [{ code: 'ENABLE', title: '启停', description: '允许按记录控制可用状态。' }],
  },
};
const selectedModeDefinition = computed(() => modes.find((item) => item.mode === selectedMode.value)!);
const selectedCapabilitySet = computed(() => new Set(selectedCapabilities.value));
const requiredCapabilityCodes = computed(() => capabilityGroups.value.required.map((fact) => fact.code));
const requiredCapabilities = computed(() => {
  const modeOwned = new Set(['TREE']);
  if (selectedMode.value !== 'LIST_CARD') modeOwned.add('SORT');
  return new Set([
    ...selectedCapabilities.value.filter((capability) => !modeOwned.has(capability)),
    ...requiredCapabilityCodes.value,
  ]);
});
const dataScopeChanged = computed(
  () =>
    selectedCapabilitySet.value.has('DATA_SCOPE') !== profile.value?.mainCapabilities.includes('DATA_SCOPE'),
);
const capabilitySelections = computed<Record<string, boolean>>(() => {
  const current = new Set(profile.value?.mainCapabilities ?? []);
  const desired = new Set(requiredCapabilities.value);
  const additions = [...desired]
    .filter((capability) => capability !== 'DATA_SCOPE' && !current.has(capability))
    .map((capability) => [capability, true] as const);
  // TREE must be removed before SORT: a tree contract owns ordering as a dependency.
  const removals = (['TREE', 'SORT', 'ENABLE'] as const)
    .filter(
      (capability) =>
        !desired.has(capability) &&
        (current.has(capability) || (capability === 'TREE' && selectedMode.value !== 'TREE_CARD')),
    )
    .map((capability) => [capability, false] as const);
  return Object.fromEntries([...additions, ...removals]);
});
const isDirty = computed(
  () =>
    selectedMode.value !== profile.value?.mode ||
    Object.keys(capabilitySelections.value).length > 0 ||
    dataScopeChanged.value,
);
useWorkspaceViewUnsavedState('业务呈现方式', () => isDirty.value);
const capabilityGroups = computed(() => {
  const facts = profile.value?.mode === selectedMode.value ? profile.value.capabilities : undefined;
  const fallback = fallbackCapabilities[selectedMode.value];
  return {
    required: facts?.required ?? fallback.required,
    recommended: facts?.recommended ?? fallback.recommended,
    optional: facts?.optional ?? fallback.optional,
  };
});
watch(
  () => props.moduleAlias,
  () => void loadProfile(),
  { immediate: true },
);
async function loadProfile() {
  loading.value = true;
  profile.value = undefined;
  try {
    const response = await moduleContext.http.request<unknown>({
      method: 'GET',
      path: `/platform.module/${encodeURIComponent(props.moduleAlias)}/overview-mode`,
    });
    profile.value = normalizeProfile(response);
    selectedMode.value = profile.value.mode;
    selectedCapabilities.value = [...profile.value.mainCapabilities];
    editing.value = false;
  } catch (cause) {
    presentPlatformError(cause, { source: 'module-experience-profile', phase: 'load' });
  } finally {
    loading.value = false;
  }
}
function chooseMode(mode: ExperienceMode) {
  if (editing.value && !saving.value) selectedMode.value = mode;
}
function setCapability(capability: string, checked: boolean) {
  if (!editing.value) return;
  selectedCapabilities.value = checked
    ? [...new Set([...selectedCapabilities.value, capability])]
    : selectedCapabilities.value.filter((code) => code !== capability);
}
async function saveProfile() {
  if (!editing.value) return;
  saving.value = true;
  try {
    const result = await moduleContext.http.request<unknown>({
      method: 'POST',
      path: `/platform.module/${encodeURIComponent(props.moduleAlias)}/overview-mode`,
      body: {
        overviewMode: modeCode(selectedMode.value),
        expectedMainMetadataVersion: profile.value?.mainMetadataVersion,
        capabilitySelections: capabilitySelections.value,
        ...(dataScopeChanged.value
          ? { dataScopeEnabled: selectedCapabilitySet.value.has('DATA_SCOPE') }
          : {}),
      },
    });
    profile.value = normalizeProfile(result, selectedMode.value);
    selectedMode.value = profile.value.mode;
    selectedCapabilities.value = [...profile.value.mainCapabilities];
    editing.value = false;
    await handlePlatformActionSuccess(result, {
      source: 'module-experience-profile',
      phase: 'action',
      fallbackMessage: '业务呈现方式已保存',
    });
  } catch (cause) {
    presentPlatformError(cause, { source: 'module-experience-profile', phase: 'action' });
  } finally {
    saving.value = false;
  }
}
function beginEditing() {
  if (!loading.value && !saving.value) editing.value = true;
}
function cancelEditing() {
  if (saving.value) return;
  selectedMode.value = profile.value?.mode ?? selectedMode.value;
  selectedCapabilities.value = [...(profile.value?.mainCapabilities ?? [])];
  editing.value = false;
}
function normalizeProfile(value: unknown, fallbackMode: ExperienceMode = 'LIST_CARD'): ExperienceProfile {
  const source = value && typeof value === 'object' ? (value as Record<string, unknown>) : {};
  const mode = normalizeMode(source.overviewMode ?? source.mode) ?? fallbackMode;
  const capabilitySource =
    source.capabilities && typeof source.capabilities === 'object'
      ? (source.capabilities as Record<string, unknown>)
      : source;
  return {
    mode,
    version:
      typeof source.moduleVersion === 'number'
        ? source.moduleVersion
        : typeof source.version === 'number'
          ? source.version
          : undefined,
    mainMetadataVersion:
      typeof source.mainMetadataVersion === 'number' ? source.mainMetadataVersion : undefined,
    mainCapabilities: Array.isArray(source.mainCapabilities)
      ? source.mainCapabilities.filter((item): item is string => typeof item === 'string')
      : [],
    capabilities: {
      required: normalizeCapabilities(capabilitySource.required ?? capabilitySource.requiredCapabilities),
      recommended: normalizeCapabilities(
        capabilitySource.recommended ?? capabilitySource.recommendedCapabilities,
      ),
      optional: normalizeCapabilities(
        capabilitySource.optional ??
          capabilitySource.optionalCapabilities ??
          capabilitySource.allowed ??
          capabilitySource.allowedCapabilities,
      ),
    },
  };
}
function normalizeMode(value: unknown): ExperienceMode | undefined {
  if (value === 'TREE_CARD' || value === 'tree_card') return 'TREE_CARD';
  if (value === 'LIST_CARD' || value === 'list_card') return 'LIST_CARD';
  if (value === 'MICRO_LIST_CARD' || value === 'micro_list_card') return 'MICRO_LIST_CARD';
  return undefined;
}
function modeCode(mode: ExperienceMode): string {
  return mode === 'TREE_CARD' ? 'tree_card' : mode === 'LIST_CARD' ? 'list_card' : 'micro_list_card';
}
function normalizeCapabilities(value: unknown): CapabilityFact[] | undefined {
  if (!Array.isArray(value)) return undefined;
  return value.map((item) => {
    if (typeof item === 'string') return { code: item, title: item };
    const source = item && typeof item === 'object' ? (item as Record<string, unknown>) : {};
    const code =
      typeof source.code === 'string'
        ? source.code
        : typeof source.capability === 'string'
          ? source.capability
          : 'UNKNOWN';
    return {
      code,
      title:
        typeof source.title === 'string'
          ? source.title
          : typeof source.label === 'string'
            ? source.label
            : code,
      description: typeof source.description === 'string' ? source.description : undefined,
    };
  });
}
</script>

<template>
  <RecordDetailPanel title="概览" :subtitle="moduleTitle ?? moduleAlias">
    <template #actions>
      <UiActionButton
        v-if="!editing"
        emphasis="primary"
        :disabled="loading"
        data-testid="edit-overview"
        @click="beginEditing"
        >编辑</UiActionButton
      >
      <template v-else>
        <UiActionButton :disabled="saving" data-testid="cancel-overview" @click="cancelEditing"
          >取消</UiActionButton
        >
        <UiActionButton
          emphasis="primary"
          :disabled="loading"
          :loading="saving"
          data-testid="save-overview"
          @click="saveProfile"
          >保存</UiActionButton
        >
      </template>
    </template>
    <UiSpin v-if="loading" tip="加载业务呈现方式" />
    <template v-else>
      <section class="module-experience-section">
        <RecordContentSectionHeading
          title="业务呈现方式"
          subtitle="决定模块管理页使用的导航与详情协作方式。"
        />
        <div class="module-experience-mode-list" role="radiogroup" aria-label="业务呈现方式">
          <button
            v-for="item in modes"
            :key="item.mode"
            class="module-experience-mode"
            :class="{ 'module-experience-mode--selected': selectedMode === item.mode }"
            type="button"
            role="radio"
            :aria-checked="selectedMode === item.mode"
            :disabled="saving || !editing"
            @click="chooseMode(item.mode)"
          >
            <strong>{{ item.title }}</strong
            ><span>{{ item.description }}</span>
          </button>
        </div>
      </section>
      <section class="module-experience-section">
        <RecordContentSectionHeading
          title="能力边界"
          :subtitle="`${selectedModeDefinition.title}下的能力约束。`"
        />
        <div class="module-experience-capabilities">
          <section
            v-for="group in ['required', 'recommended', 'optional'] as const"
            :key="group"
            class="module-experience-capability-group"
          >
            <RecordContentSectionHeading
              :title="group === 'required' ? '必备能力' : group === 'recommended' ? '推荐能力' : '选配能力'"
            />
            <UiEmpty
              v-if="!capabilityGroups[group].length"
              :description="
                group === 'required'
                  ? '该呈现方式没有额外必备能力'
                  : group === 'recommended'
                    ? '当前没有推荐能力'
                    : '当前没有选配能力'
              "
            />
            <div v-else class="module-experience-capability-list">
              <div
                v-for="fact in capabilityGroups[group]"
                :key="fact.code"
                class="module-experience-capability"
              >
                <UiCheckbox
                  :checked="group === 'required' || selectedCapabilitySet.has(fact.code)"
                  :disabled="group === 'required' || !editing || saving"
                  @update:checked="(checked) => setCapability(fact.code, checked)"
                  >{{ fact.title }}</UiCheckbox
                >
                <span>{{ fact.description }}</span>
              </div>
            </div>
          </section>
        </div>
      </section>
    </template>
  </RecordDetailPanel>
</template>

<style scoped>
.module-experience-section {
  display: grid;
  gap: 10px;
  min-width: 0;
}
.module-experience-section + .module-experience-section {
  margin-top: 20px;
}
.module-experience-mode-list {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}
.module-experience-mode {
  display: grid;
  gap: 6px;
  min-width: 0;
  padding: 12px;
  border: 1px solid var(--muyun-border);
  border-radius: 8px;
  background: var(--muyun-surface);
  color: var(--muyun-text-body);
  cursor: pointer;
  text-align: left;
}
.module-experience-mode:hover:not(:disabled),
.module-experience-mode:focus-visible {
  border-color: var(--muyun-primary);
  background: var(--muyun-primary-surface, var(--muyun-hover));
  outline: none;
}
.module-experience-mode--selected {
  border-color: var(--muyun-primary);
  box-shadow: inset 0 0 0 1px var(--muyun-primary);
}
.module-experience-mode:disabled {
  cursor: not-allowed;
  opacity: 0.65;
}
.module-experience-mode strong {
  color: var(--muyun-text);
  font-size: 14px;
  line-height: 20px;
}
.module-experience-mode span {
  color: var(--muyun-text-muted);
  font-size: 12px;
  line-height: 18px;
}
.module-experience-capabilities {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}
.module-experience-capability-group {
  display: grid;
  align-content: start;
  gap: 8px;
  min-width: 0;
  padding: 10px;
  border: 1px solid var(--muyun-border);
  border-radius: 8px;
  background: var(--muyun-hover-subtle);
}
.module-experience-capability-list {
  display: grid;
  gap: 10px;
}
.module-experience-capability {
  display: grid;
  gap: 4px;
}
.module-experience-capability span,
.module-experience-capability small {
  color: var(--muyun-text-muted);
  font-size: 12px;
  line-height: 18px;
}
@media (max-width: 900px) {
  .module-experience-mode-list,
  .module-experience-capabilities {
    grid-template-columns: 1fr;
  }
}
</style>
