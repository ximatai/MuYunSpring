import { computed, onActivated, onBeforeUnmount, onDeactivated, ref } from 'vue';
import { prepareCodeRule, type CodeRule, type CodeTarget } from './codeConfiguration';
import { AssistantCapabilityUsageError, type LanguageContext } from '@muyun/web-core';

/** The editor and assistant own one candidate and one saved baseline. */
export function useCodeConfigurationSession(language: LanguageContext) {
  const draft = ref<CodeRule>();
  const original = ref('');
  const active = ref(true);
  const recoveryRequired = ref(false);
  onActivated(() => {
    active.value = true;
  });
  onDeactivated(() => {
    active.value = false;
  });
  onBeforeUnmount(() => {
    active.value = false;
  });
  const dirty = computed(() => !!draft.value && JSON.stringify(draft.value) !== original.value);
  function reset(rule?: CodeRule) {
    draft.value = rule ? (JSON.parse(JSON.stringify(rule)) as CodeRule) : undefined;
    original.value = draft.value ? JSON.stringify(draft.value) : '';
  }
  function prepare(patch: Record<string, unknown>, target: CodeTarget, current: () => boolean) {
    if (!draft.value) throw new AssistantCapabilityUsageError('请先打开编号规则');
    const before = JSON.stringify(draft.value);
    const candidate = JSON.parse(before) as CodeRule;
    const allowed = ['title', 'mode', 'linkedUpdate', 'allowRecycle', 'segments', 'sequencePolicy'];
    if (!Object.keys(patch).length || Object.keys(patch).some((key) => !allowed.includes(key)))
      throw new AssistantCapabilityUsageError('只能调整规则名称、编号方式、格式与流水策略');
    if ('title' in patch && (typeof patch.title !== 'string' || patch.title.length > 200))
      throw new AssistantCapabilityUsageError('规则名称无效');
    if (
      'mode' in patch &&
      (typeof patch.mode !== 'string' || !['AUTO', 'MANUAL', 'AUTO_WITH_MANUAL_EDIT'].includes(patch.mode))
    )
      throw new AssistantCapabilityUsageError('编号方式无效');
    for (const key of ['linkedUpdate', 'allowRecycle'])
      if (key in patch && typeof patch[key] !== 'boolean')
        throw new AssistantCapabilityUsageError('策略必须为布尔值');
    function object(value: unknown, keys: string[]) {
      if (
        !value ||
        typeof value !== 'object' ||
        Array.isArray(value) ||
        Object.keys(value).some((key) => !keys.includes(key))
      )
        throw new AssistantCapabilityUsageError('格式只能包含声明的配置字段，不能覆盖持久标识');
      return value as Record<string, unknown>;
    }
    if ('segments' in patch) {
      if (!Array.isArray(patch.segments) || patch.segments.length > 40)
        throw new AssistantCapabilityUsageError('编号片段最多40项');
      for (const item of patch.segments) {
        const segment = object(item, [
          'segmentType',
          'sourceRef',
          'fixedValue',
          'formulaExpr',
          'dateFormat',
          'length',
          'padMode',
          'padChar',
          'separator',
          'sequenceBasis',
          'nullPolicy',
          'truncatePolicy',
          'sortOrder',
          'mappings',
        ]);
        if (
          typeof segment.segmentType !== 'string' ||
          ![
            'CONSTANT',
            'FIELD_VALUE',
            'CONTEXT_VAR',
            'SYSTEM_TIME',
            'SEQUENCE',
            'VALUE_MAPPING',
            'FORMULA',
          ].includes(String(segment.segmentType))
        )
          throw new AssistantCapabilityUsageError('编号片段类型无效');
        if (!Array.isArray(segment.mappings) || segment.mappings.length > 100)
          throw new AssistantCapabilityUsageError('片段映射无效');
        for (const mapping of segment.mappings) {
          const entry = object(mapping, ['sourceValue', 'targetValue', 'defaultMapping', 'enabled']);
          if (
            typeof entry.sourceValue !== 'string' ||
            typeof entry.targetValue !== 'string' ||
            typeof entry.defaultMapping !== 'boolean' ||
            typeof entry.enabled !== 'boolean'
          )
            throw new AssistantCapabilityUsageError('映射值无效');
        }
        for (const key of [
          'sourceRef',
          'fixedValue',
          'formulaExpr',
          'dateFormat',
          'padMode',
          'padChar',
          'separator',
          'nullPolicy',
          'truncatePolicy',
        ])
          if (segment[key] != null && typeof segment[key] !== 'string')
            throw new AssistantCapabilityUsageError('片段文本无效');
        if (segment.sequenceBasis != null && typeof segment.sequenceBasis !== 'boolean')
          throw new AssistantCapabilityUsageError('计数依据无效');
      }
    }
    if (patch.sequencePolicy != null)
      object(patch.sequencePolicy, [
        'startValue',
        'stepValue',
        'sequenceLength',
        'maxValue',
        'resetPolicy',
        'overflowPolicy',
      ]);
    if (patch.sequencePolicy != null) {
      const policy = patch.sequencePolicy as Record<string, unknown>;
      if (
        typeof policy.resetPolicy !== 'string' ||
        typeof policy.overflowPolicy !== 'string' ||
        !['NONE', 'DAY', 'MONTH', 'YEAR'].includes(String(policy.resetPolicy)) ||
        !['ERROR', 'RESET'].includes(String(policy.overflowPolicy))
      )
        throw new AssistantCapabilityUsageError('流水周期或溢出策略无效');
    }
    const sources = new Set(target.sources.map((source) => source.fieldName));
    const restrictedFormat = candidate.segments.some(
      (segment) =>
        (segment.sourceRef && !sources.has(segment.sourceRef)) ||
        [...(segment.formulaExpr?.matchAll(/\{([^{}]+)\}/g) ?? [])].some((match) => !sources.has(match[1]!)),
    );
    if (restrictedFormat && ('segments' in patch || ('mode' in patch && patch.mode !== candidate.mode)))
      throw new AssistantCapabilityUsageError('当前格式含目录之外来源，请人工维护格式和编号方式');
    Object.assign(candidate, patch);
    const prepared = prepareCodeRule(candidate, language);
    // A human may have switched to manual mode without saving yet. Keep their
    // hidden format candidate intact when the assistant only edits other properties.
    const normalized = restrictedFormat
      ? { ...prepared, segments: candidate.segments, sequencePolicy: candidate.sequencePolicy }
      : prepared;
    for (const segment of 'segments' in patch ? normalized.segments : []) {
      if (segment.sourceRef && !sources.has(segment.sourceRef))
        throw new AssistantCapabilityUsageError('只能引用当前可见来源字段');
      for (const match of segment.formulaExpr?.matchAll(/\{([^{}]+)\}/g) ?? [])
        if (!sources.has(match[1]!)) throw new AssistantCapabilityUsageError('公式只能引用当前可见来源字段');
    }
    return () => {
      if (!current() || !active.value || recoveryRequired.value || JSON.stringify(draft.value) !== before)
        throw new AssistantCapabilityUsageError('编码草稿已变化，请重新读取');
      draft.value = normalized;
      return { changedFields: Object.keys(patch), unsaved: dirty.value, saved: false };
    };
  }
  return { draft, original, active, dirty, recoveryRequired, reset, prepare };
}
