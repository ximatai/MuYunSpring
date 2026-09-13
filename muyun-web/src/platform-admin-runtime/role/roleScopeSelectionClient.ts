import type { HttpClient } from '@muyun/web-core';

export type RoleScopeSelectionLevel = 'TENANT' | 'ORGANIZATION';

/**
 * The role module owns this vocabulary because the opaque keys are resolved by
 * RoleScopePageSelectionResolver. Other object pickers must not reuse it as a
 * surrogate tenant or organization API.
 */
export interface RoleScopeSelectionCandidate {
  id: string;
  selectionKey: string;
  label: string;
  secondaryLabel?: string;
  expandable: boolean;
}

export interface RoleScopeSelectionDirectSelection {
  selectionKey: string;
  label: string;
}

export interface RoleScopeSelectionNavigation {
  navigationKey: string;
  level: RoleScopeSelectionLevel;
  label: string;
}

export interface RoleScopeSelectionDescriptor {
  directSelections: RoleScopeSelectionDirectSelection[];
  navigations: RoleScopeSelectionNavigation[];
}

export interface RoleScopeSelectionCandidatePage {
  records: RoleScopeSelectionCandidate[];
  hasMore: boolean;
  nextPage?: number;
}

export interface RoleScopeSelectionCandidatesQuery {
  /** The descriptor-issued key that authorizes this navigation step. */
  navigationKey: string;
  level: RoleScopeSelectionLevel;
  keyword?: string;
  /** Organization ID only. The caller receives it as an opaque candidate fact. */
  parentId?: string;
  pageNum?: number;
  pageSize?: number;
}

export interface RoleScopeSelectionClient {
  descriptor(): Promise<RoleScopeSelectionDescriptor>;
  candidates(query: RoleScopeSelectionCandidatesQuery): Promise<RoleScopeSelectionCandidatePage>;
}

const descriptorPath = '/iam.role/scope-selection/descriptor';
const candidatesPath = '/iam.role/scope-selection/candidates';

/**
 * Dedicated facade for the role range navigator. The browser receives only
 * selections the role service has already admitted; it never reads tenant or
 * organization modules to reconstruct authorization scope.
 */
export function createRoleScopeSelectionClient(http: HttpClient): RoleScopeSelectionClient {
  return {
    async descriptor() {
      return normalizeDescriptor(await http.request<unknown>({ path: descriptorPath }));
    },
    async candidates(query) {
      const pageNum = positiveInteger(query.pageNum) ?? 1;
      const pageSize = positiveInteger(query.pageSize) ?? 100;
      return normalizeCandidatePage(
        await http.request<unknown>({
          method: 'POST',
          path: candidatesPath,
          body: {
            navigationKey: requiredText(query.navigationKey, 'navigationKey'),
            level: query.level,
            keyword: normalizedText(query.keyword),
            parentId: normalizedText(query.parentId),
            page: { pageNum, pageSize },
          },
        }),
        pageNum,
      );
    },
  };
}

function normalizeDescriptor(value: unknown): RoleScopeSelectionDescriptor {
  const source = recordOf(value);
  return {
    directSelections: arrayOf(source.directSelections).flatMap(normalizeDirectSelection),
    navigations: arrayOf(source.navigations).flatMap(normalizeNavigation),
  };
}

function normalizeCandidatePage(value: unknown, requestedPageNum: number): RoleScopeSelectionCandidatePage {
  const source = recordOf(value);
  const records = arrayOf(source.records).flatMap(normalizeCandidate);
  const pageNum = numberOf(source.pageNum) ?? requestedPageNum;
  const pages = numberOf(source.pages);
  const totalKnown = booleanOf(source.totalKnown, true);
  const hasMore = totalKnown && pages != null ? pageNum < pages : records.length > 0;
  return {
    records,
    hasMore,
    ...(hasMore ? { nextPage: pageNum + 1 } : {}),
  };
}

function normalizeDirectSelection(value: unknown): RoleScopeSelectionDirectSelection[] {
  const source = recordOf(value);
  const selectionKey = textOf(source.selectionKey);
  if (!selectionKey) return [];
  return [{ selectionKey, label: textOf(source.title ?? source.label) ?? selectionKey }];
}

function normalizeNavigation(value: unknown): RoleScopeSelectionNavigation[] {
  const source = recordOf(value);
  const navigationKey = textOf(source.navigationKey);
  const level = levelOf(source.level);
  if (!navigationKey || !level) return [];
  return [{ navigationKey, level, label: textOf(source.title ?? source.label) ?? navigationKey }];
}

function normalizeCandidate(value: unknown): RoleScopeSelectionCandidate[] {
  const source = recordOf(value);
  const id = textOf(source.id);
  const selectionKey = textOf(source.selectionKey);
  if (!id || !selectionKey) return [];
  return [
    {
      id,
      selectionKey,
      label: textOf(source.title ?? source.label) ?? selectionKey,
      ...(textOf(source.subtitle ?? source.secondaryLabel)
        ? {
            secondaryLabel: textOf(source.subtitle ?? source.secondaryLabel),
          }
        : {}),
      expandable: booleanOf(source.expandable, false),
    },
  ];
}

function levelOf(value: unknown): RoleScopeSelectionLevel | undefined {
  return value === 'TENANT' || value === 'ORGANIZATION' ? value : undefined;
}

function recordOf(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : {};
}

function arrayOf(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

function textOf(value: unknown) {
  return typeof value === 'string' ? normalizedText(value) : undefined;
}

function normalizedText(value: string | undefined) {
  const normalized = value?.trim();
  return normalized || undefined;
}

function requiredText(value: string, name: string) {
  const normalized = normalizedText(value);
  if (!normalized) throw new Error(`角色归属范围${name}不能为空`);
  return normalized;
}

function booleanOf(value: unknown, fallback: boolean) {
  return typeof value === 'boolean' ? value : fallback;
}

function numberOf(value: unknown) {
  return typeof value === 'number' && Number.isInteger(value) && value > 0 ? value : undefined;
}

function positiveInteger(value: number | undefined) {
  return typeof value === 'number' && Number.isInteger(value) && value > 0 ? value : undefined;
}
