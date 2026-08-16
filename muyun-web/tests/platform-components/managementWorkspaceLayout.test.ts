import { describe, expect, it } from 'vitest';
import {
  MANAGEMENT_WORKSPACE_LAYOUT,
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
