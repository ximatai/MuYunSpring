import type {
  ResolvedReferenceFieldDescriptor,
  WebReferenceResolveItem,
  WebTreeNode,
} from '@muyun/web-contracts';
import type { ReferenceResolveClient } from '@muyun/web-core';
import type {
  ReferencePickerCandidate,
  ReferencePickerBrowseScope,
  ReferencePickerProvider,
  ReferencePickerTreeNode,
} from './referencePickerModel';
import {
  missingReferencePickerDependencies,
  unsupportedReferencePickerConfiguration,
} from './referencePickerReadError';

export interface SourceReferencePickerProviderOptions {
  sourceModuleAlias: string;
  fieldName: string;
  reference: ResolvedReferenceFieldDescriptor;
  /** Obtained at request time so a retained provider does not capture a replaced module context. */
  resolver: () => ReferenceResolveClient;
  /** Read at request time so a stable provider always uses the current draft. */
  formValues: () => Record<string, unknown>;
  source: () => { recordId: string } | undefined;
}

/**
 * Adapts the source-field resolver to the neutral picker contract.  It deliberately carries no
 * target-module query fallback: the declaring source field remains the authority for candidate
 * eligibility, projections and patches.
 */
export function createSourceReferencePickerProvider({
  sourceModuleAlias,
  fieldName,
  reference,
  resolver,
  formValues,
  source,
}: SourceReferencePickerProviderOptions): ReferencePickerProvider {
  const candidateOf = (item: {
    id: string;
    title?: string;
    projections?: Record<string, unknown>;
    affectPatch?: Record<string, unknown>;
  }): ReferencePickerCandidate => ({
    id: item.id,
    title: item.title ?? item.id,
    projections: item.projections,
    affectPatch: item.affectPatch,
  });
  const treeNodeOf = (node: WebTreeNode<WebReferenceResolveItem>): ReferencePickerTreeNode => ({
    record: candidateOf(node.record),
    ...(node.children.length ? { children: node.children.map(treeNodeOf) } : {}),
  });
  const resolvePath = reference.resolvePath ?? '';
  const missingDependencies = () => {
    const values = formValues();
    return (reference.candidateDependencies ?? [])
      .filter(({ sourceField, required }) => required && isBlank(values[sourceField]))
      .map(({ sourceField }) => sourceField);
  };
  const requireDependencies = () => {
    const fields = missingDependencies();
    if (fields.length > 0) throw missingReferencePickerDependencies(fields);
  };

  const treeProvider =
    reference.pickerMode === 'TREE'
      ? {
          async loadTree({ scope }: { scope: ReferencePickerBrowseScope }) {
            if (scope.selections.length > 0) {
              throw unsupportedReferencePickerConfiguration('当前引用来源不支持范围导航');
            }
            requireDependencies();
            const response = await resolver().resolve(fieldName, {
              mode: 'TREE',
              formValues: formValues(),
              source: source(),
            });
            return (response.tree ?? []).map(treeNodeOf);
          },
        }
      : {};

  return {
    identity: {
      targetModuleAlias: reference.targetModuleAlias,
      // The source module is part of the identity even where multiple source modules expose the
      // same field name and route suffix.
      source: { kind: 'sourceField', id: `${sourceModuleAlias}:${fieldName}:${resolvePath}` },
    },
    async searchPage({ keyword, pageNum, pageSize, scope }) {
      if (scope.selections.length > 0) {
        throw unsupportedReferencePickerConfiguration('当前引用来源不支持范围导航');
      }
      requireDependencies();
      const response = await resolver().resolve(fieldName, {
        mode: 'QUERY',
        fuzzy: keyword || undefined,
        page: { pageNum, pageSize },
        formValues: formValues(),
        source: source(),
      });
      return { records: response.options.map(candidateOf), total: response.total };
    },
    ...treeProvider,
    async resolve(ids) {
      if (!ids.length) return [];
      requireDependencies();
      const response = await resolver().resolve(fieldName, {
        mode: 'TRANSLATE',
        values: ids,
        formValues: formValues(),
        source: source(),
      });
      const candidatesById = new Map(
        response.results.flatMap((result) =>
          result.item && ids.includes(result.item.id)
            ? [[result.item.id, candidateOf(result.item)] as const]
            : [],
        ),
      );
      return ids.flatMap((id) => {
        const candidate = candidatesById.get(id);
        return candidate ? [candidate] : [];
      });
    },
  };
}

function isBlank(value: unknown) {
  return value == null || (typeof value === 'string' && value.trim().length === 0);
}

/**
 * Only source identity, persisted record scope and declared candidate dependencies invalidate a
 * picker.  This avoids resetting pagination or re-querying for unrelated form edits.
 */
export function sourceReferencePickerReloadKey(
  sourceModuleAlias: string,
  fieldName: string,
  reference: ResolvedReferenceFieldDescriptor,
  record: Record<string, unknown> | undefined,
): string {
  const dependencies = Object.fromEntries(
    (reference.candidateDependencies ?? []).map(({ sourceField }) => [sourceField, record?.[sourceField]]),
  );
  return JSON.stringify({
    sourceModuleAlias,
    fieldName,
    resolvePath: reference.resolvePath,
    recordId: record?.id,
    tenantId: record?.tenantId,
    dependencies,
  });
}
