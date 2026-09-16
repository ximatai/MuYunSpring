import { describe, expect, it, vi } from 'vitest';
import { useModulePageActions } from '@/dynamic-page-runtime/composables/useModulePageActions';

describe('module page actions', () => {
  it('reports an enhancement failure as an unsuccessful execution without rethrowing', async () => {
    const result = await useModulePageActions().runEnhancementAction(
      { key: 'rejecting-action', run: vi.fn().mockRejectedValue(new Error('拒绝执行')) },
      {},
    );

    expect(result).toBe(false);
  });

  it('marks a completed enhancement action as successful', async () => {
    const run = vi.fn().mockResolvedValue(undefined);

    await expect(
      useModulePageActions().runEnhancementAction({ key: 'completed-action', run }, {}),
    ).resolves.toBe(true);
    expect(run).toHaveBeenCalledOnce();
  });
});
