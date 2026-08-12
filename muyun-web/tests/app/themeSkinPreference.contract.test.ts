import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { expect, it } from 'vitest';

it('keeps the device-local skin when the account has no backend preference yet', () => {
  const appSource = readFileSync(resolve(import.meta.dirname, '../../src/App.vue'), 'utf8');

  expect(appSource).toMatch(
    /userPreferences\.restore\(THEME_SKIN_PREFERENCE_KEY, themeSkinId\.value, \{\s*persistence: 'backend'/,
  );
});
