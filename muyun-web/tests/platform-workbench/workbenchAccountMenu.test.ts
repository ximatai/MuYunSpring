import { expect, it } from 'vitest';
import { createLanguageContext } from '@muyun/web-core';
import { createWorkbenchAccountMenu } from '@/platform-workbench/workbenchAccountMenu';
const language = createLanguageContext();
it('owns defaults, gates tokens, and only lets host configuration narrow standard visibility', () => {
  expect(createWorkbenchAccountMenu(language).map((item) => item.key)).toEqual([
    'languageSettings',
    'themeSkin',
    'changePassword',
    'profile',
    'logout',
  ]);
  expect(createWorkbenchAccountMenu(language, {}, true).map((item) => item.key)).toContain('accessTokens');
  const hidden = createWorkbenchAccountMenu(
    language,
    { hiddenCommands: ['accessTokens', 'themeSkin'] },
    true,
  ).map((item) => item.key);
  expect(hidden).not.toContain('accessTokens');
  expect(hidden).not.toContain('themeSkin');
});
it('appends namespaced business commands in stable groups and leaves the supplied config intact', () => {
  const options = {
    contributions: [
      { key: 'mr.last' as const, title: 'Last', order: 10 },
      { key: 'mr.first' as const, title: 'First', order: -1 },
      { key: 'mr.preferences' as const, title: 'Preferences', group: 'preferences' as const },
      { key: 'mr.hidden' as const, title: 'Hidden', visible: false },
    ],
  };
  expect(createWorkbenchAccountMenu(language, options).map((item) => item.key)).toEqual([
    'languageSettings',
    'themeSkin',
    'mr.preferences',
    'changePassword',
    'profile',
    'mr.first',
    'mr.last',
    'logout',
  ]);
  expect(options.contributions[0]!.key).toBe('mr.last');
});
it.each(['accessTokens', 'profile', 'logout', 'identity', 'workspace', 'bad key'])(
  'rejects an unnamespaced or reserved override %s',
  (key) => {
    expect(() =>
      createWorkbenchAccountMenu(language, {
        contributions: [{ key: key as 'mr.invalid', title: 'Override' }],
      }),
    ).toThrow('Invalid');
  },
);
it('rejects duplicate business commands and invalid sort order', () => {
  expect(() =>
    createWorkbenchAccountMenu(language, {
      contributions: [
        { key: 'mr.a', title: 'A' },
        { key: 'mr.a', title: 'B' },
      ],
    }),
  ).toThrow('duplicate');
  expect(() =>
    createWorkbenchAccountMenu(language, { contributions: [{ key: 'mr.a', title: 'A', order: NaN }] }),
  ).toThrow('order');
});
