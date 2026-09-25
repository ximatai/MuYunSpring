import { generatedBusinessFieldName } from './metadataNaming';
import type { ComponentDragPayload } from './pageCompositionDragPayload';
import type { PageComposerField } from './pageCompositionDraftState';

export interface ComponentDefinition {
  component: ComponentDragPayload['component'];
  title: string;
  fieldSpecAlias: string;
}
export interface ComponentCatalog {
  relationId: string;
  metadataVersion: number;
  components: ComponentDefinition[];
  canCreateChild: boolean;
}

export interface PendingComponentField {
  key: string;
  component: ComponentDragPayload['component'];
  title: string;
  required?: boolean;
  fieldSpecAlias: string;
}

/** Transport only component intent; metadata specifications remain server-owned. */
export function componentFieldDefinition(input: PendingComponentField) {
  return {
    key: input.key,
    component: input.component,
    title: input.title,
    required: input.required,
  };
}

/** Resolve the title only at publication; the editor keeps its stable temporary identity. */
export function componentFieldForSave(input: PendingComponentField) {
  return {
    ...componentFieldDefinition(input),
    suggestedName: generatedBusinessFieldName(input.title, 'BASIC'),
  };
}

export function componentField(input: PendingComponentField): PageComposerField {
  return {
    pending: true,
    required: input.required ?? false,
    id: input.key,
    fieldName: `field${input.key}`,
    title: input.title,
    fieldSpecAlias: input.fieldSpecAlias,
  };
}

type ComponentTree = {
  quickSearchFields?: string[];
  querySummaries?: Array<{ fieldName?: string; groupByField?: string }>;
  nodes?: Array<{
    slot: string;
    titleField?: string;
    secondaryField?: string;
    fields?: Array<string | { field: string }>;
    groups?: Array<{ fields?: Array<string | { field: string }> }>;
  }>;
};

/** Direct main-entity references only; child fields belong to their own metadata scope. */
function fieldUsages(tree: ComponentTree): Array<{ field: string; area: string }> {
  const usages = (tree.quickSearchFields ?? []).map((field) => ({ field, area: '快速查询' }));
  for (const summary of tree.querySummaries ?? [])
    for (const field of [summary.fieldName, summary.groupByField])
      if (field) usages.push({ field, area: '汇总统计' });
  const separate = tree.nodes?.some((node) => node.slot === 'detail');
  for (const node of tree.nodes ?? []) {
    if (node.slot === 'explorer') {
      for (const field of [node.titleField, node.secondaryField])
        if (field) usages.push({ field, area: '导航' });
    }
    const area =
      node.slot === 'list'
        ? '列表'
        : node.slot === 'detail'
          ? '详情'
          : node.slot === 'form'
            ? separate
              ? '表单'
              : '详情 / 表单'
            : undefined;
    if (!area) continue;
    for (const entry of [
      ...(node.fields ?? []),
      ...(node.groups ?? []).flatMap((group) => group.fields ?? []),
    ])
      usages.push({ field: typeof entry === 'string' ? entry : entry.field, area });
  }
  return usages;
}

/** Build once per draft change; field consumers share the same index. */
export function componentFieldUsageIndex(treeJson: string): Map<string, string[]> {
  const index = new Map<string, string[]>();
  for (const { field, area } of fieldUsages(JSON.parse(treeJson))) {
    const areas = index.get(field) ?? [];
    if (!areas.includes(area)) areas.push(area);
    index.set(field, areas);
  }
  return index;
}

/** Only referenced definitions are created on save; unused session entries remain available for undo. */
export function placedComponentFields(inputs: PendingComponentField[], treeJson: string) {
  const index = componentFieldUsageIndex(treeJson);
  return inputs.filter((input) => index.has(`field${input.key}`));
}

export function componentFieldUsage(fieldName: string, treeJson: string): string[] {
  return componentFieldUsageIndex(treeJson).get(fieldName) ?? [];
}

export function sameTitleFields(title: string, fieldId: string, fields: PageComposerField[]) {
  const normalized = title.trim();
  return normalized
    ? fields.filter((field) => field.id !== fieldId && field.title.trim() === normalized)
    : [];
}
