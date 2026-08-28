import { computed, ref } from 'vue';

export type PlatformUiSetType = 'LIST' | 'FORM' | 'DETAIL' | 'REFERENCE';
export type PlatformUiClientType = 'WEB' | 'APP';

export interface PlatformUiSet {
  id?: string;
  version?: number;
  title?: string;
  alias?: string;
  setType?: PlatformUiSetType;
  defaultSet?: boolean;
  enabled?: boolean;
  sortOrder?: number;
}

export interface PlatformUiConfig {
  id?: string;
  version?: number;
  title?: string;
  uiSetId?: string;
  clientType?: PlatformUiClientType;
  enabled?: boolean;
  published?: boolean;
  sortOrder?: number;
}

export interface PlatformUiConfigField {
  id?: string;
  version?: number;
  uiConfigId?: string;
  moduleMetadataFieldId?: string;
  fieldUiControlAlias?: string;
  visible?: boolean;
  readOnly?: boolean;
  requiredOverride?: boolean;
  columnSpan?: number;
  enabled?: boolean;
  sortOrder?: number;
}

export interface UiModuleFieldOption {
  value: string;
  label: string;
}

export type UiOrchestrationMode =
  | 'view'
  | 'create-set'
  | 'edit-set'
  | 'create-config'
  | 'edit-config'
  | 'create-field'
  | 'edit-field';

/** Selection and editor lifecycle for a module's nested UI configuration workspace. */
export function createUiOrchestrationState() {
  const uiSets = ref<PlatformUiSet[]>([]);
  const configs = ref<PlatformUiConfig[]>([]);
  const fields = ref<PlatformUiConfigField[]>([]);
  const selectedUiSetId = ref<string>();
  const selectedUiConfigId = ref<string>();
  const mode = ref<UiOrchestrationMode>('view');
  const uiSetDraft = ref<PlatformUiSet>(emptyUiSetDraft());
  const uiConfigDraft = ref<PlatformUiConfig>(emptyUiConfigDraft());
  const fieldDraft = ref<PlatformUiConfigField>(emptyUiConfigFieldDraft());

  const selectedUiSet = computed(() => uiSets.value.find((item) => item.id === selectedUiSetId.value));
  const selectedUiConfig = computed(() => configs.value.find((item) => item.id === selectedUiConfigId.value));
  const configPublished = computed(() => selectedUiConfig.value?.published === true);
  const setEditorOpen = computed(() => mode.value === 'create-set' || mode.value === 'edit-set');
  const configEditorOpen = computed(() => mode.value === 'create-config' || mode.value === 'edit-config');
  const fieldEditorOpen = computed(() => mode.value === 'create-field' || mode.value === 'edit-field');

  function handleUiSetsLoaded(loaded: PlatformUiSet[]) {
    uiSets.value = loaded;
    const availableIds = new Set(loaded.map((item) => item.id).filter(Boolean) as string[]);
    if (!selectedUiSetId.value || !availableIds.has(selectedUiSetId.value)) {
      selectedUiSetId.value = loaded[0]?.id;
    }
  }

  function handleConfigsLoaded(loaded: PlatformUiConfig[]) {
    configs.value = loaded;
    const availableIds = new Set(loaded.map((item) => item.id).filter(Boolean) as string[]);
    if (!selectedUiConfigId.value || !availableIds.has(selectedUiConfigId.value)) {
      selectedUiConfigId.value = loaded[0]?.id;
    }
  }

  function handleFieldsLoaded(loaded: PlatformUiConfigField[]) {
    fields.value = loaded;
  }

  function selectUiSet(uiSet: PlatformUiSet): boolean {
    mode.value = 'view';
    if (!uiSet.id || uiSet.id === selectedUiSetId.value) return false;
    selectedUiSetId.value = uiSet.id;
    selectedUiConfigId.value = undefined;
    configs.value = [];
    fields.value = [];
    return true;
  }

  function selectUiConfig(uiConfig: PlatformUiConfig): boolean {
    mode.value = 'view';
    if (!uiConfig.id || uiConfig.id === selectedUiConfigId.value) return false;
    selectedUiConfigId.value = uiConfig.id;
    fields.value = [];
    return true;
  }

  function startCreateSet() {
    uiSetDraft.value = emptyUiSetDraft();
    mode.value = 'create-set';
  }

  function startEditSet() {
    if (!selectedUiSet.value) return;
    uiSetDraft.value = { ...selectedUiSet.value };
    mode.value = 'edit-set';
  }

  function startCreateConfig() {
    if (!selectedUiSet.value?.id) return;
    uiConfigDraft.value = { ...emptyUiConfigDraft(), uiSetId: selectedUiSet.value.id };
    mode.value = 'create-config';
  }

  function startEditConfig() {
    if (!selectedUiConfig.value || configPublished.value) return;
    uiConfigDraft.value = { ...selectedUiConfig.value };
    mode.value = 'edit-config';
  }

  function startCreateField() {
    if (!selectedUiConfig.value?.id || configPublished.value) return;
    fieldDraft.value = { ...emptyUiConfigFieldDraft(), uiConfigId: selectedUiConfig.value.id };
    mode.value = 'create-field';
  }

  function startEditField(field: PlatformUiConfigField) {
    if (!selectedUiConfig.value?.id || configPublished.value) return;
    fieldDraft.value = { ...field };
    mode.value = 'edit-field';
  }

  function cancelEditor() {
    mode.value = 'view';
  }

  return {
    uiSets,
    configs,
    fields,
    selectedUiSetId,
    selectedUiConfigId,
    mode,
    uiSetDraft,
    uiConfigDraft,
    fieldDraft,
    selectedUiSet,
    selectedUiConfig,
    configPublished,
    setEditorOpen,
    configEditorOpen,
    fieldEditorOpen,
    handleUiSetsLoaded,
    handleConfigsLoaded,
    handleFieldsLoaded,
    selectUiSet,
    selectUiConfig,
    startCreateSet,
    startEditSet,
    startCreateConfig,
    startEditConfig,
    startCreateField,
    startEditField,
    cancelEditor,
  };
}

export function emptyUiSetDraft(): PlatformUiSet {
  return { title: '', alias: '', setType: 'LIST', defaultSet: false, enabled: true };
}

export function emptyUiConfigDraft(): PlatformUiConfig {
  return { title: '', clientType: 'WEB', enabled: true, published: false };
}

export function emptyUiConfigFieldDraft(): PlatformUiConfigField {
  return { moduleMetadataFieldId: undefined, visible: true, readOnly: false, columnSpan: 1, enabled: true };
}

export function normalizeUiSetDraft(draft: PlatformUiSet): PlatformUiSet {
  return { ...draft, title: trimOptional(draft.title), alias: trimOptional(draft.alias) };
}

export function normalizeUiConfigDraft(draft: PlatformUiConfig): PlatformUiConfig {
  return { ...draft, title: trimOptional(draft.title) };
}

export function normalizeUiConfigFieldDraft(draft: PlatformUiConfigField): PlatformUiConfigField {
  return {
    ...draft,
    fieldUiControlAlias: trimOptional(draft.fieldUiControlAlias),
    columnSpan: normalizeColumnSpan(draft.columnSpan),
  };
}

export function isValidUiSetDraft(draft: PlatformUiSet): boolean {
  return Boolean(draft.alias?.trim() && draft.title?.trim() && draft.setType);
}

export function isValidUiConfigFieldDraft(draft: PlatformUiConfigField): boolean {
  return Boolean(draft.moduleMetadataFieldId);
}

export function uiSetItemTitle(uiSet: PlatformUiSet): string {
  return uiSet.title?.trim() || uiSet.alias?.trim() || uiSet.id || '未命名 UI 配置集';
}

export function uiConfigItemTitle(config: PlatformUiConfig): string {
  return config.title?.trim() || config.clientType || config.id || '未命名 UI 配置';
}

/** A dynamic module is executable only when it has enabled, published WEB list and form configs. */
export function pageExecutionStatusOf(uiSets: PlatformUiSet[], configs: PlatformUiConfig[]): string {
  const publishedFor = (type: PlatformUiSetType) =>
    uiSets.some(
      (uiSet) =>
        uiSet.id &&
        uiSet.setType === type &&
        uiSet.enabled !== false &&
        configs.some(
          (config) =>
            config.uiSetId === uiSet.id &&
            config.clientType === 'WEB' &&
            config.enabled !== false &&
            config.published,
        ),
    );
  const missing = [
    ...(publishedFor('LIST') ? [] : ['Web 列表']),
    ...(publishedFor('FORM') ? [] : ['Web 表单']),
  ];
  return missing.length
    ? `已发布但未可执行：缺少${missing.join('、')}配置`
    : '可执行：已具备已发布的 Web 列表与表单配置';
}

function trimOptional(value: string | undefined): string | undefined {
  const normalized = value?.trim();
  return normalized || undefined;
}

function normalizeColumnSpan(value: number | undefined): number {
  if (!Number.isFinite(value)) return 1;
  return Math.max(1, Math.trunc(value ?? 1));
}
