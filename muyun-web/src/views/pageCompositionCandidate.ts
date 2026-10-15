import { platformLanguageContext, type LanguageContext } from '@muyun/web-core';
import type {
  PageComposerField,
  PageComposerFieldProperties,
  PageComposerLayout,
  PageComposerRelation,
} from './pageCompositionDraftState';
import { orderedFormItems } from './pageCompositionDraftState';

export interface PageCompositionCandidateInput {
  list?: Array<{ fieldName: string; properties?: PageComposerFieldProperties }>;
  form?: Array<{ fieldName: string; properties?: PageComposerFieldProperties }>;
  detail?: Array<{ fieldName: string; properties?: PageComposerFieldProperties }>;
  /** Supplied relations replace the complete child layout; omission preserves it. */
  relations?: Array<{
    relationCode: string;
    title?: string;
    fields: Array<{ fieldName: string; properties?: PageComposerFieldProperties }>;
  }>;
  quickSearchFields?: string[];
}
export interface PageCompositionCandidateState {
  list: PageComposerField[];
  form: PageComposerLayout;
  detail: PageComposerLayout;
  separateDetail: boolean;
  columns: boolean;
  quickSearchFields: string[];
  fields: PageComposerField[];
  searchableFields: string[];
  relations: PageComposerRelation[];
  availableRelations: PageComposerRelation[];
}

/** Resolve every source and placement before mutating the shared editor. */
export function preparePageCompositionCandidate(
  current: PageCompositionCandidateState,
  input: PageCompositionCandidateInput,
) {
  const fields = new Map(
    current.fields
      .filter((field) => !field.unavailable && !field.pending)
      .map((field) => [field.fieldName, field]),
  );
  const resolve = (
    items: NonNullable<PageCompositionCandidateInput['list']>,
    previous: PageComposerField[],
    layout?: PageComposerLayout,
    directory = fields,
  ) => {
    const grouped = new Set(
      layout?.groups.flatMap((group) => group.fields.map((field) => field.fieldName)) ?? [],
    );
    return items.map(({ fieldName, properties }) => {
      const source = directory.get(fieldName);
      if (!source) throw new Error(`Field is unavailable: ${fieldName}`);
      if (grouped.has(fieldName)) throw new Error(`Field already belongs to a group: ${fieldName}`);
      if (layout && source.platformReadOnly && properties?.readOnly === false)
        throw new Error(`Field must remain read-only: ${fieldName}`);
      const existing = previous.find((field) => field.fieldName === fieldName);
      const nextProperties = {
        ...existing?.properties,
        ...properties,
        ...(layout && source.platformReadOnly ? { readOnly: true } : {}),
      };
      return { ...source, properties: nextProperties };
    });
  };
  const layout = (previous: PageComposerLayout, items: PageCompositionCandidateInput['form']) => {
    if (!items) return previous;
    const nextFields = resolve(items, previous.fields, previous);
    const remaining = nextFields.map((field) => ({ kind: 'field' as const, id: field.id }));
    const order = orderedFormItems(previous.fields, previous.groups, previous.order).flatMap((item) =>
      item.kind === 'group' ? [item] : remaining.length ? [remaining.shift()!] : [],
    );
    return { ...previous, fields: nextFields, order: [...order, ...remaining] };
  };
  if (input.list && !current.columns) throw new Error('The current template has no list columns');
  if (input.detail && !current.separateDetail) throw new Error('Detail currently shares the form layout');
  if (
    input.quickSearchFields?.some((field) => !fields.has(field) || !current.searchableFields.includes(field))
  )
    throw new Error('Quick search contains an unavailable or non-searchable field');
  const availableRelations = new Map(
    current.availableRelations
      .filter((relation) => !relation.unavailable && !relation.pending)
      .map((relation) => [relation.relationCode, relation]),
  );
  const relations =
    input.relations?.map((inputRelation) => {
      const source = availableRelations.get(inputRelation.relationCode);
      if (!source) throw new Error(`Child relation is unavailable: ${inputRelation.relationCode}`);
      const previous = current.relations.find(
        (relation) => relation.relationCode === inputRelation.relationCode,
      );
      const directory = new Map(
        source.fields
          .filter((field) => !field.unavailable && !field.pending)
          .map((field) => [field.fieldName, field]),
      );
      return {
        ...source,
        title: inputRelation.title ?? previous?.title ?? source.title,
        fields: resolve(inputRelation.fields, previous?.fields ?? [], undefined, directory),
      };
    }) ?? current.relations;
  return {
    relations,
    list: input.list ? resolve(input.list, current.list) : current.list,
    form: layout(current.form, input.form),
    detail: layout(current.detail, input.detail),
    quickSearchFields: input.quickSearchFields ?? current.quickSearchFields,
  };
}

/** Compare template declarations, including manual edits, without exposing arbitrary UI JSON to the model. */
export function pageCompositionChangeLines(
  before: string | undefined,
  after: string,
  fieldTitle: (name: string) => string = (name) => name,
  language: LanguageContext = platformLanguageContext,
): string[] {
  if (!before || before === after) return [];
  try {
    const previous = JSON.parse(before);
    const current = JSON.parse(after);
    const lines: string[] = [];
    const labels: Record<string, string> = {
      list: language.t('platform.page.composition.list', '列表'),
      form: language.t('platform.screen.PageCompositionEditor.form', '表单'),
      detail: language.t('platform.screen.PageCompositionEditor.details', '详情'),
      explorer: language.t('platform.screen.pageCompositionComponents.navigation', '导航'),
    };
    for (const slot of ['list', 'form', 'detail', 'explorer']) {
      const oldNode = previous.nodes?.find((node: { slot: string }) => node.slot === slot);
      const newNode = current.nodes?.find((node: { slot: string }) => node.slot === slot);
      if (JSON.stringify(oldNode) === JSON.stringify(newNode)) continue;
      const display = (
        node: { fields?: Array<string | { field: string; props?: unknown }> } | undefined,
        prefix = '',
      ) =>
        node?.fields
          ?.map((field) =>
            typeof field === 'string'
              ? fieldTitle(prefix + field)
              : `${fieldTitle(prefix + field.field)}（${Object.entries(field.props ?? {})
                  .map(
                    ([key, value]) =>
                      `${({ label: language.t('platform.screen.CrudRecordListExplorer.title', '标题'), width: language.t('platform.screen.PageCompositionEditor.column-width', '列宽'), align: language.t('platform.screen.PageCompositionEditor.alignment', '对齐'), columnSpan: language.t('platform.screen.pageCompositionCandidate.column-span', '占列'), readOnly: language.t('platform.screen.metadataSourceTree.read-only', '只读'), fieldUiControlAlias: language.t('platform.screen.pageCompositionCandidate.control', '控件') } as Record<string, string>)[key] ?? key}：${String(value)}`,
                  )
                  .join('；')}）`,
          )
          .join('、') || language.t('platform.screen.pageCompositionCandidate.no-root-fields', '无根字段');
      lines.push(`${labels[slot]}：${display(oldNode)} → ${display(newNode)}`);
      if (JSON.stringify(oldNode?.groups) !== JSON.stringify(newNode?.groups))
        lines.push(
          language.t(
            'platform.screen.pageCompositionCandidate.value-groups-changed',
            '{value1}分组已调整。',
            { value1: labels[slot] },
          ),
        );
      if (JSON.stringify(oldNode?.relations) !== JSON.stringify(newNode?.relations)) {
        const order = (node: typeof oldNode) =>
          (node?.relations ?? [])
            .map((relation: { relation: string; title?: string }) => relation.title ?? relation.relation)
            .join('、') || language.t('platform.screen.metadataChangeSetSubmission.none', '无');
        if (order(oldNode) !== order(newNode))
          lines.push(
            language.t(
              'platform.screen.pageCompositionCandidate.detail-order-value-value',
              '明细排列：{value1} → {value2}',
              { value1: order(oldNode), value2: order(newNode) },
            ),
          );
        for (const code of new Set<string>(
          [...(oldNode?.relations ?? []), ...(newNode?.relations ?? [])].map((relation) => relation.relation),
        )) {
          const oldRelation = oldNode?.relations?.find(
            (relation: { relation: string }) => relation.relation === code,
          );
          const newRelation = newNode?.relations?.find(
            (relation: { relation: string }) => relation.relation === code,
          );
          if (JSON.stringify(oldRelation) !== JSON.stringify(newRelation))
            lines.push(
              language.t(
                'platform.screen.pageCompositionCandidate.detail-value-value-value',
                '明细“{value1}”：{value2} → {value3}',
                {
                  value1: newRelation?.title ?? oldRelation?.title ?? code,
                  value2: oldRelation
                    ? display(oldRelation, code + '.')
                    : language.t('platform.screen.pageCompositionCandidate.not-displayed', '未展示'),
                  value3: newRelation
                    ? display(newRelation, code + '.')
                    : language.t(
                        'platform.screen.pageCompositionCandidate.remove-display-without-deleting-data',
                        '移除展示（不删除数据）',
                      ),
                },
              ),
            );
        }
      }
    }
    if (JSON.stringify(previous.quickSearchFields) !== JSON.stringify(current.quickSearchFields))
      lines.push(
        language.t(
          'platform.screen.pageCompositionCandidate.quick-search-value-value',
          '快速查询：{value1} → {value2}',
          {
            value1:
              (previous.quickSearchFields ?? []).map(fieldTitle).join('、') ||
              language.t('platform.screen.metadataChangeSetSubmission.none', '无'),
            value2:
              (current.quickSearchFields ?? []).map(fieldTitle).join('、') ||
              language.t('platform.screen.metadataChangeSetSubmission.none', '无'),
          },
        ),
      );
    if (JSON.stringify(previous.actions) !== JSON.stringify(current.actions))
      lines.push(
        language.t(
          'platform.screen.pageCompositionCandidate.page-action-presentation-changed',
          '页面动作展示已调整。',
        ),
      );
    if (JSON.stringify(previous.querySummaries) !== JSON.stringify(current.querySummaries))
      lines.push(
        language.t('platform.screen.pageCompositionCandidate.query-summaries-changed', '查询统计已调整。'),
      );
    if (!lines.length)
      lines.push(
        language.t(
          'platform.screen.pageCompositionCandidate.page-template-or-presentation-configuration-changed',
          '页面模板或展示配置已调整。',
        ),
      );
    return lines;
  } catch {
    return [
      language.t(
        'platform.screen.pageCompositionCandidate.the-page-configuration-contains-unparseable-content-correct-it-before-prechecking',
        '页面配置存在无法解析的内容，请修正后预检。',
      ),
    ];
  }
}
