import { platformLanguageContext, type LanguageContext } from '@muyun/web-core';
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
export function dictionaryRadioEligibilityIssue(
  input: DictionaryRadioEligibilityInput,
  language: LanguageContext = platformLanguageContext,
): string | undefined {
  if (input.optionSourceType !== 'dictionary')
    return language.t('platform.page.radio.dictionary-only', '仅支持数据字典字段');
  if (input.optionSelectionMode !== 'SINGLE')
    return language.t('platform.page.radio.single-value-only', 'radio 仅支持单值字段');
  if (input.loadError)
    return language.t('platform.page.radio.read-failed', '无法读取字典候选：{error}', {
      error: input.loadError,
    });
  if (!input.facts) return language.t('platform.page.radio.loading', '正在读取字典候选');
  if (input.facts.hasHierarchy)
    return language.t(
      'platform.page.radio.hierarchy-unavailable',
      '层级字典不能使用 radio，请改用下拉或弹框',
    );
  if (input.facts.enabledCandidateCount > input.maxOptions)
    return language.t(
      'platform.page.radio.too-many-options',
      '当前有 {count} 个启用候选，radio 最多支持 {maximum} 个',
      { count: input.facts.enabledCandidateCount, maximum: input.maxOptions },
    );
  return undefined;
}
