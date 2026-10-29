import {
  actionResultData,
  presentationLabel,
  OperationUsageError,
  codeOperationErrorMessage,
  platformLanguageContext,
  type LanguageContext,
  type HttpClient,
} from '@muyun/web-core';

import { decodeNumberEditorValue } from '@muyun/platform-components';
import type { TextPresentation } from '@muyun/web-contracts';

export type CodeMode = 'AUTO' | 'MANUAL' | 'AUTO_WITH_MANUAL_EDIT';
export type CodeRole = 'PRIMARY' | 'NORMAL';
export type CodeTrigger = 'SAVE' | 'EXPLICIT';
export const codeSegmentTypes = [
  'CONSTANT',
  'FIELD_VALUE',
  'CONTEXT_VAR',
  'SYSTEM_TIME',
  'SEQUENCE',
  'VALUE_MAPPING',
  'FORMULA',
] as const;
export type CodeSegmentType = (typeof codeSegmentTypes)[number];
export interface CodeSource {
  fieldName: string;
  title: string;
  titleText?: TextPresentation;
  ownerTitle?: string | null;
  type: string;
  length?: number | null;
}
export function codeSourceTitle(source: CodeSource, language: LanguageContext = platformLanguageContext) {
  const title = presentationLabel(source.title, source.titleText, language);
  return source.ownerTitle ? `${source.ownerTitle} · ${title}` : title;
}
export interface CodeField {
  fieldName: string;
  title: string;
  length?: number | null;
  metadataFieldId?: string | null;
  bindingId?: string | null;
  bindingVersion?: number | null;
  role?: CodeRole | null;
  trigger?: CodeTrigger | null;
}
export interface CodeTarget {
  moduleAlias: string;
  moduleTitle?: string;
  moduleTitleText?: TextPresentation;
  entityAlias: string;
  title: string;
  source: 'STATIC' | 'DYNAMIC';
  relationId?: string | null;
  fields: CodeField[];
  sources: CodeSource[];
}
export interface CodeConfigurationAvailability {
  configurable: boolean;
  reason?: 'no-model' | 'no-fields' | 'not-declared' | 'unavailable' | null;
}
export function readCodeConfigurationAvailability(http: HttpClient, moduleAlias: string) {
  return http.request<CodeConfigurationAvailability>({
    path: `/platform.module/${encodeURIComponent(moduleAlias)}/code-configuration`,
  });
}
export interface CodeMapping {
  id?: string;
  version?: number;
  sourceValue: string;
  targetValue: string;
  defaultMapping: boolean;
  enabled: boolean;
}
export interface CodeSegment {
  id?: string;
  version?: number;
  segmentType: CodeSegmentType;
  sourceRef?: string | null;
  fixedValue?: string | null;
  formulaExpr?: string | null;
  dateFormat?: string | null;
  length?: number | null;
  padMode?: string;
  padChar?: string | null;
  separator?: string | null;
  sequenceBasis?: boolean;
  nullPolicy?: string;
  truncatePolicy?: string;
  sortOrder: number;
  mappings: CodeMapping[];
}
/** Check source references against the current catalog without evaluating formulas. */
export function codeSegmentSourcesVisible(segment: CodeSegment, sources: ReadonlySet<string>): boolean {
  return (
    (!segment.sourceRef || sources.has(segment.sourceRef)) &&
    [...(segment.formulaExpr?.matchAll(/\{([^{}]+)\}/g) ?? [])].every((match) => sources.has(match[1]!))
  );
}

export interface CodeSequencePolicy {
  id?: string;
  version?: number;
  startValue: number | null;
  stepValue: number | null;
  sequenceLength: number | null;
  maxValue?: number | null;
  resetPolicy: string;
  overflowPolicy: string;
}
export interface CodeRule {
  id?: string;
  version?: number;
  title: string;
  moduleAlias: string;
  entityAlias: string;
  fieldName: string;
  moduleMetadataFieldId?: string | null;
  metadataFieldId?: string | null;
  fieldRole?: CodeRole;
  fieldTrigger?: CodeTrigger;
  mode: CodeMode;
  enabled: boolean;
  linkedUpdate: boolean;
  allowRecycle: boolean;
  orgScopeType: 'GLOBAL' | 'ORG';
  orgScopeId?: string | null;
  globalDefault: boolean;
  effectiveFrom?: string | null;
  effectiveTo?: string | null;
  segments: CodeSegment[];
  sequencePolicy: CodeSequencePolicy | null;
}
export interface CodePreview {
  value: string;
  segments: Array<{ value: string; segmentType: CodeSegmentType }>;
}
export function newCodeSegment(type: CodeSegmentType = 'CONSTANT'): CodeSegment {
  return {
    segmentType: type,
    sortOrder: 0,
    fixedValue: type === 'CONSTANT' ? 'NO-' : null,
    dateFormat: type === 'SYSTEM_TIME' ? 'YYYYMMDD' : null,
    length: null,
    padMode: 'NONE',
    truncatePolicy: 'NONE',
    nullPolicy: 'ERROR',
    sequenceBasis: false,
    mappings: [],
  };
}
export function newCodeSequence(): CodeSequencePolicy {
  return {
    startValue: 1,
    stepValue: 1,
    sequenceLength: 4,
    resetPolicy: 'NONE',
    overflowPolicy: 'ERROR',
    maxValue: null,
  };
}
export function newCodeRule(target: CodeTarget, field: CodeField): CodeRule {
  return {
    title: field.title,
    moduleAlias: target.moduleAlias,
    entityAlias: target.entityAlias,
    fieldName: field.fieldName,
    moduleMetadataFieldId: field.bindingId,
    metadataFieldId: field.metadataFieldId,
    fieldRole: field.role ?? 'NORMAL',
    fieldTrigger: field.trigger ?? 'SAVE',
    mode: 'AUTO',
    enabled: false,
    linkedUpdate: false,
    allowRecycle: false,
    orgScopeType: 'GLOBAL',
    globalDefault: true,
    orgScopeId: null,
    segments: [newCodeSegment(), newCodeSegment('SEQUENCE')],
    sequencePolicy: newCodeSequence(),
  };
}
/** Match the rule's supported dependency kinds without parsing formula expressions in the browser. */
export function codeRuleSupportsRenumbering(rule: CodeRule): boolean {
  return (
    rule.mode !== 'MANUAL' &&
    rule.fieldTrigger !== 'EXPLICIT' &&
    rule.segments.some(
      (segment) =>
        segment.segmentType === 'FORMULA' ||
        (['FIELD_VALUE', 'CONTEXT_VAR', 'VALUE_MAPPING'].includes(segment.segmentType) &&
          !!segment.sourceRef?.trim()),
    )
  );
}
/** Validate the active draft without discarding temporarily inactive editor values. */
export function validateCodeRuleDraft(
  rule: CodeRule,
  language: LanguageContext = platformLanguageContext,
): void {
  if (rule.mode !== 'MANUAL') {
    const values = [
      ...rule.segments.map((segment) => segment.length),
      rule.sequencePolicy?.startValue,
      rule.sequencePolicy?.stepValue,
      rule.sequencePolicy?.sequenceLength,
      rule.sequencePolicy?.maxValue,
    ];
    if (values.some((value) => value != null && !Number.isSafeInteger(value)))
      throw new OperationUsageError(
        codeOperationErrorMessage(
          language,
          'platform.code.config.integer-required',
          '流水策略和片段长度须为可准确表示的整数。',
        ),
      );
  }
}

/** Normalize only at the save/preview boundary, never while editing a candidate. */
export function prepareCodeRule(
  rule: CodeRule,
  language: LanguageContext = platformLanguageContext,
): CodeRule {
  validateCodeRuleDraft(rule, language);
  const result = JSON.parse(JSON.stringify(rule)) as CodeRule;
  result.orgScopeId = result.orgScopeType === 'GLOBAL' ? null : result.orgScopeId;
  result.globalDefault = result.orgScopeType === 'GLOBAL';
  result.effectiveFrom ||= null;
  result.effectiveTo ||= null;
  if (result.mode === 'MANUAL') {
    result.segments = [];
    result.sequencePolicy = null;
    result.linkedUpdate = false;
  } else if (!result.segments.some((segment) => segment.segmentType === 'SEQUENCE'))
    result.sequencePolicy = null;
  if (!codeRuleSupportsRenumbering(result)) result.linkedUpdate = false;
  result.segments.forEach((segment, index) => {
    segment.sortOrder = index;
  });
  return result;
}
/** Direct inputs only; formula samples can be added from the source catalog without parsing formulas in the browser. */
export function codeTrialSources(target: CodeTarget, rule?: CodeRule): CodeSource[] {
  const selected = new Map<string, CodeSource>();
  for (const segment of rule?.segments ?? []) {
    if (!['FIELD_VALUE', 'VALUE_MAPPING', 'CONTEXT_VAR'].includes(segment.segmentType) || !segment.sourceRef)
      continue;
    const source = target.sources.find((source) => source.fieldName === segment.sourceRef);
    if (source) selected.set(source.fieldName, source);
    else if (segment.segmentType === 'CONTEXT_VAR')
      selected.set(segment.sourceRef, {
        fieldName: segment.sourceRef,
        title: segment.sourceRef,
        type: 'STRING',
      });
  }
  return [...selected.values()];
}
/** Source types are shared by static Java declarations and dynamic column metadata. */
export function codeTrialSourceKind(source: CodeSource): 'boolean' | 'number' | 'date' | 'datetime' | 'text' {
  if (/^(BOOLEAN|BOOL)$/i.test(source.type)) return 'boolean';
  if (/^(INT|INTEGER|LONG|DECIMAL|DOUBLE|FLOAT|NUMBER|BIGDECIMAL)$/i.test(source.type)) return 'number';
  if (/^(DATE|LOCALDATE)$/i.test(source.type)) return 'date';
  if (/^(DATETIME|LOCALDATETIME)$/i.test(source.type)) return 'datetime';
  return 'text';
}
/** No formula evaluation in the browser; only coerce explicitly entered trial values. */
export function codeTrialContext(
  sources: readonly CodeSource[],
  values: Record<string, string>,
  language: LanguageContext = platformLanguageContext,
): Record<string, unknown> {
  const result: Record<string, unknown> = {};
  for (const source of sources) {
    const value = values[source.fieldName];
    if (value === undefined || value === '') continue;
    if (codeTrialSourceKind(source) === 'number') {
      const type = source.type.toUpperCase();
      const number = decodeNumberEditorValue(
        value,
        type === 'LONG'
          ? 'LONG'
          : ['DECIMAL', 'BIGDECIMAL'].includes(type)
            ? 'DECIMAL'
            : ['INT', 'INTEGER'].includes(type)
              ? 'INTEGER'
              : undefined,
      );
      if (number === undefined)
        throw new OperationUsageError(
          codeOperationErrorMessage(
            language,
            'platform.code.config.number-required',
            '{field}请输入有效数字',
            { field: codeSourceTitle(source, language) },
          ),
        );
      result[source.fieldName] = number;
    } else if (codeTrialSourceKind(source) === 'boolean') {
      if (value !== 'true' && value !== 'false')
        throw new OperationUsageError(
          codeOperationErrorMessage(
            language,
            'platform.code.config.boolean-required',
            '{field}请输入 true 或 false',
            { field: codeSourceTitle(source, language) },
          ),
        );
      result[source.fieldName] = value === 'true';
    } else result[source.fieldName] = value;
  }
  return result;
}
export function createCodeConfigurationClient(
  http: HttpClient,
  language: LanguageContext = platformLanguageContext,
) {
  const root = '/platform.code_rule';
  return {
    targets: (moduleAlias?: string) =>
      http.request<CodeTarget[]>({
        path: root + '/targets' + (moduleAlias ? `?moduleAlias=${encodeURIComponent(moduleAlias)}` : ''),
      }),
    rules: (target: CodeTarget) =>
      http.request<CodeRule[]>({
        path: `${root}/rules?moduleAlias=${encodeURIComponent(target.moduleAlias)}&entityAlias=${encodeURIComponent(target.entityAlias)}`,
      }),
    async bind(target: CodeTarget, field: CodeField, role: CodeRole | null, trigger: CodeTrigger) {
      return actionResultData(
        await http.request<CodeTarget>({
          method: 'POST',
          path: root + '/binding',
          body: {
            moduleAlias: target.moduleAlias,
            entityAlias: target.entityAlias,
            fieldName: field.fieldName,
            role,
            trigger,
            expectedVersion: field.bindingVersion ?? null,
          },
        }),
      );
    },
    async save(rule: CodeRule, requestId?: string) {
      return actionResultData(
        await http.request<CodeRule>({
          method: 'POST',
          path: requestId ? `${root}/save-requests/${encodeURIComponent(requestId)}` : root + '/saveTree',
          body: prepareCodeRule(rule, language),
        }),
      );
    },
    view: (id: string) => http.request<CodeRule>({ path: `${root}/viewTree/${encodeURIComponent(id)}` }),
    receipt: (reference: {
      requestId: string;
      moduleAlias: string;
      entityAlias: string;
      fieldName: string;
    }) =>
      http.request<{
        committed: boolean;
        cancelled: boolean;
        ruleId: string | null;
        savedVersion: number | null;
      }>({
        path: `${root}/save-requests/${encodeURIComponent(reference.requestId)}?moduleAlias=${encodeURIComponent(reference.moduleAlias)}&entityAlias=${encodeURIComponent(reference.entityAlias)}&fieldName=${encodeURIComponent(reference.fieldName)}`,
      }),
    abandon: (reference: {
      requestId: string;
      moduleAlias: string;
      entityAlias: string;
      fieldName: string;
    }) =>
      http.request<{
        committed: boolean;
        cancelled: boolean;
        ruleId: string | null;
        savedVersion: number | null;
      }>({
        method: 'POST',
        path: `${root}/save-requests/${encodeURIComponent(reference.requestId)}/abandon`,
        body: {
          moduleAlias: reference.moduleAlias,
          entityAlias: reference.entityAlias,
          fieldName: reference.fieldName,
        },
      }),
    async preview(
      rule: CodeRule,
      context: Record<string, unknown>,
      sequenceValue: number,
      signal?: AbortSignal,
    ) {
      if (!Number.isSafeInteger(sequenceValue) || sequenceValue < 0)
        throw new OperationUsageError(
          codeOperationErrorMessage(
            language,
            'platform.code.config.trial-sequence-required',
            '试算流水值须为非负整数。',
          ),
        );
      return actionResultData(
        await http.request<CodePreview>({
          method: 'POST',
          path: root + '/preview',
          ...(signal ? { signal } : {}),
          body: {
            rule: prepareCodeRule(rule, language),
            context,
            sequenceValue,
            organizationId: rule.orgScopeId ?? null,
          },
        }),
      );
    },
  };
}
