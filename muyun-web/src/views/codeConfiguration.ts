import {
  actionResultData,
  OperationUsageError,
  codeOperationErrorMessage,
  platformLanguageContext,
  type LanguageContext,
  type HttpClient,
} from '@muyun/web-core';

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
  type: string;
  length?: number | null;
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
  entityAlias: string;
  title: string;
  source: 'STATIC' | 'DYNAMIC';
  relationId?: string | null;
  fields: CodeField[];
  sources: CodeSource[];
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
    length: type === 'SEQUENCE' ? 4 : null,
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
export function prepareCodeRule(
  rule: CodeRule,
  language: LanguageContext = platformLanguageContext,
): CodeRule {
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
  result.segments.forEach((segment, index) => {
    segment.sortOrder = index;
  });
  return result;
}
export function codeTrialSources(target: CodeTarget, rule?: CodeRule): CodeSource[] {
  const sources = [...target.sources];
  for (const segment of rule?.segments ?? []) {
    if (
      segment.segmentType === 'CONTEXT_VAR' &&
      segment.sourceRef &&
      !sources.some((source) => source.fieldName === segment.sourceRef)
    ) {
      sources.push({ fieldName: segment.sourceRef, title: segment.sourceRef, type: 'STRING' });
    }
  }
  return sources;
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
    if (/^(INT|INTEGER|LONG|DECIMAL|DOUBLE|FLOAT|NUMBER|BIGDECIMAL)$/i.test(source.type)) {
      const number = Number(value);
      if (!Number.isFinite(number))
        throw new OperationUsageError(
          codeOperationErrorMessage(
            language,
            'platform.code.config.number-required',
            '{field}请输入有效数字',
            { field: source.title },
          ),
        );
      result[source.fieldName] = number;
    } else if (/^(BOOLEAN|BOOL)$/i.test(source.type)) {
      if (value !== 'true' && value !== 'false')
        throw new OperationUsageError(
          codeOperationErrorMessage(
            language,
            'platform.code.config.boolean-required',
            '{field}请输入 true 或 false',
            { field: source.title },
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
    async save(rule: CodeRule) {
      return actionResultData(
        await http.request<CodeRule>({
          method: 'POST',
          path: root + '/saveTree',
          body: prepareCodeRule(rule, language),
        }),
      );
    },
    async preview(rule: CodeRule, context: Record<string, unknown>, sequenceValue: number) {
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
