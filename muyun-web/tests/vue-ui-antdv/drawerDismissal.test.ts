import { describe, expect, it, vi } from 'vitest';
import {
  allowsUiDrawerOutsideDismissal,
  mayCloseUiDrawer,
  resolveUiDrawerDismissal,
} from '@/vue-ui-antdv/drawerDismissal';

describe('drawer dismissal', () => {
  it('preserves the legacy outside-close default while resolving semantic policies', () => {
    expect(resolveUiDrawerDismissal({ closeOnOutside: false })).toBe('explicit');
    expect(resolveUiDrawerDismissal({ closeOnOutside: true })).toBe('dismissible');
    expect(resolveUiDrawerDismissal({ dismissal: 'guarded', closeOnOutside: true })).toBe('guarded');
  });

  it('does not expose an unguarded guarded drawer to outside dismissal', () => {
    expect(allowsUiDrawerOutsideDismissal({ dismissal: 'guarded' })).toBe(false);
    expect(allowsUiDrawerOutsideDismissal({ dismissal: 'guarded', beforeClose: () => true })).toBe(true);
  });

  it('lets a session guard reject an outside close and receives the close source', async () => {
    const beforeClose = vi.fn(async () => false);

    await expect(mayCloseUiDrawer({ dismissal: 'guarded', beforeClose }, 'outside')).resolves.toBe(false);
    expect(beforeClose).toHaveBeenCalledWith('outside');
  });

  it('keeps explicit drawers closable through their close button', async () => {
    const beforeClose = vi.fn(() => true);

    await expect(mayCloseUiDrawer({ dismissal: 'explicit', beforeClose }, 'close-button')).resolves.toBe(true);
    expect(beforeClose).toHaveBeenCalledWith('close-button');
  });
});
