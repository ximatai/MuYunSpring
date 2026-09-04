import { describe, expect, it } from 'vitest';
import { reconcileSelectedKey, reconcileSelectedKeys } from '@/platform-components';

describe('selection refresh reconciliation', () => {
  it('retains a still-present focused key and otherwise uses the caller fallback', () => {
    expect(reconcileSelectedKey('field-1', ['metadata-1', 'field-1'], 'metadata-1')).toBe('field-1');
    expect(reconcileSelectedKey('field-1', ['metadata-1'], 'metadata-1')).toBe('metadata-1');
  });

  it('removes only selections no longer present after a result refresh', () => {
    expect(reconcileSelectedKeys(['one', 'two', 'three'], ['one', 'three'])).toEqual(['one', 'three']);
  });
});
