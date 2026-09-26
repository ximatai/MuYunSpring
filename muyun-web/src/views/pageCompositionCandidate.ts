import type {
  PageComposerField,
  PageComposerFieldProperties,
  PageComposerLayout,
} from './pageCompositionDraftState';
import { orderedFormItems } from './pageCompositionDraftState';

export interface PageCompositionCandidateInput {
  list?: Array<{ fieldName: string; properties?: PageComposerFieldProperties }>;
  form?: Array<{ fieldName: string; properties?: PageComposerFieldProperties }>;
  detail?: Array<{ fieldName: string; properties?: PageComposerFieldProperties }>;
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
  ) => {
    const grouped = new Set(
      layout?.groups.flatMap((group) => group.fields.map((field) => field.fieldName)) ?? [],
    );
    return items.map(({ fieldName, properties }) => {
      const source = fields.get(fieldName);
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
  return {
    list: input.list ? resolve(input.list, current.list) : current.list,
    form: layout(current.form, input.form),
    detail: layout(current.detail, input.detail),
    quickSearchFields: input.quickSearchFields ?? current.quickSearchFields,
  };
}

/** Compare template declarations, including manual edits, without exposing arbitrary UI JSON to the model. */
export function pageCompositionChangeLines(before: string | undefined, after: string): string[] {
  if (!before || before === after) return [];
  try {
    const previous = JSON.parse(before);
    const current = JSON.parse(after);
    const lines: string[] = [];
    const labels: Record<string, string> = { list: '列表', form: '表单', detail: '详情', explorer: '导航' };
    for (const slot of ['list', 'form', 'detail', 'explorer']) {
      const oldNode = previous.nodes?.find((node: { slot: string }) => node.slot === slot);
      const newNode = current.nodes?.find((node: { slot: string }) => node.slot === slot);
      if (JSON.stringify(oldNode) === JSON.stringify(newNode)) continue;
      const display = (node: { fields?: Array<string | { field: string; props?: unknown }> } | undefined) =>
        node?.fields
          ?.map((field) =>
            typeof field === 'string'
              ? field
              : `${field.field}（${Object.entries(field.props ?? {})
                  .map(
                    ([key, value]) =>
                      `${({ label: '标题', width: '列宽', align: '对齐', columnSpan: '占列', readOnly: '只读', fieldUiControlAlias: '控件' } as Record<string, string>)[key] ?? key}：${String(value)}`,
                  )
                  .join('；')}）`,
          )
          .join('、') || '无根字段';
      lines.push(`${labels[slot]}：${display(oldNode)} → ${display(newNode)}`);
      if (JSON.stringify(oldNode?.groups) !== JSON.stringify(newNode?.groups))
        lines.push(`${labels[slot]}分组已调整。`);
      if (JSON.stringify(oldNode?.relations) !== JSON.stringify(newNode?.relations))
        lines.push(`${labels[slot]}关联区域已调整。`);
    }
    if (JSON.stringify(previous.quickSearchFields) !== JSON.stringify(current.quickSearchFields))
      lines.push(
        `快速查询：${(previous.quickSearchFields ?? []).join('、') || '无'} → ${(current.quickSearchFields ?? []).join('、') || '无'}`,
      );
    if (JSON.stringify(previous.actions) !== JSON.stringify(current.actions))
      lines.push('页面动作展示已调整。');
    if (JSON.stringify(previous.querySummaries) !== JSON.stringify(current.querySummaries))
      lines.push('查询统计已调整。');
    if (!lines.length) lines.push('页面模板或展示配置已调整。');
    return lines;
  } catch {
    return ['页面配置存在无法解析的内容，请修正后预检。'];
  }
}
