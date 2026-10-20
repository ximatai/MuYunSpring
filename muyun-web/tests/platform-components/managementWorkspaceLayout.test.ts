import { describe, expect, it } from 'vitest';
import {
  MANAGEMENT_WORKSPACE_LAYOUT,
  workspaceAutoCollapsedIds,
  listDetailWorkspaceMinWidth,
} from '../../src/platform-components/managementWorkspaceLayout';

describe('management workspace layout', () => {
  it('reserves the platform minimum width for every visible navigator, list and detail surface', () => {
    expect(MANAGEMENT_WORKSPACE_LAYOUT).toMatchObject({
      explorerWidth: 280,
      listMinWidth: 720,
      detailMinWidth: 560,
      columnGap: 12,
    });
    expect(listDetailWorkspaceMinWidth(0)).toBe(1292);
    expect(listDetailWorkspaceMinWidth(1)).toBe(1584);
    expect(listDetailWorkspaceMinWidth(2)).toBe(1876);
  });
});

it('budgets the rail and folds ancestors first while preserving manual state', () => {
  const explorers = [
    { id: 'tenant', collapsed: false },
    { id: 'module', collapsed: false },
  ];
  const fold = (width: number) =>
    workspaceAutoCollapsedIds({ width, explorerCount: 2, explorers, primaryMinWidth: 560 });
  expect(fold(1144)).toEqual([]);
  expect(fold(1143)).toEqual(['tenant']);
  expect(fold(900)).toEqual(['tenant']);
  expect(fold(899)).toEqual(['tenant', 'module']);
  expect(fold(0)).toEqual([]);
  explorers[1]!.collapsed = true;
  expect(fold(900)).toEqual(['tenant']);
  expect(fold(899)).toEqual(['tenant', 'module']);
  expect(explorers[0]!.collapsed).toBe(false);
});
