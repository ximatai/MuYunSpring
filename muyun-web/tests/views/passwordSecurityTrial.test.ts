import { describe, expect, it } from 'vitest';
import { evaluatePasswordRules } from '@/views/passwordSecurityTrial';

describe('password trial', () => {
  it('evaluates the supplied draft without including disabled rules', () => {
    expect(
      evaluatePasswordRules(
        [
          { pattern: '^.{6,}$', message: 'length' },
          { pattern: '.*[0-9].*', message: 'digit', enabled: false },
          { pattern: '[invalid', message: 'unsupported' },
        ],
        'abcdef',
      ),
    ).toEqual([
      { key: '0:^.{6,}$', label: 'length', status: 'passed' },
      { key: '2:[invalid', label: 'unsupported', status: 'unsupported' },
    ]);
  });
});
