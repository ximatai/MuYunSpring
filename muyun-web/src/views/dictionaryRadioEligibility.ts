/** Facts loaded from the dictionary candidate endpoint for radio presentation preflight. */
export interface DictionaryRadioCandidateFacts {
  enabledCandidateCount: number;
  hasHierarchy: boolean;
}

export interface DictionaryRadioEligibilityInput {
  optionSourceType?: string;
  optionSelectionMode?: 'SINGLE' | 'MULTIPLE';
  facts?: DictionaryRadioCandidateFacts;
  loadError?: string;
  maxOptions: number;
}

/**
 * Radio is a presentation choice over a single, flat and small dictionary. Keep this decision
 * independent from the composer view so selected-field hints, preview guards and publish guards
 * all enforce the same rule.
 */
export function dictionaryRadioEligibilityIssue(input: DictionaryRadioEligibilityInput): string | undefined {
  if (input.optionSourceType !== 'dictionary') return '仅支持数据字典字段';
  if (input.optionSelectionMode !== 'SINGLE') return 'radio 仅支持单值字段';
  if (input.loadError) return `无法读取字典候选：${input.loadError}`;
  if (!input.facts) return '正在读取字典候选';
  if (input.facts.hasHierarchy) return '层级字典不能使用 radio，请改用下拉或弹框';
  if (input.facts.enabledCandidateCount > input.maxOptions)
    return `当前有 ${input.facts.enabledCandidateCount} 个启用候选，radio 最多支持 ${input.maxOptions} 个`;
  return undefined;
}
