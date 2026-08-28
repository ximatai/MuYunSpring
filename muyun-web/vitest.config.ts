import { defineConfig, mergeConfig } from 'vitest/config';
import { playwright } from '@vitest/browser-playwright';
import { createViteConfig } from './vite.config';

const browserTestEnabled = process.env.MUYUN_BROWSER_TEST === 'true';

export default mergeConfig(
  createViteConfig('test'),
  defineConfig({
    test: {
      projects: [
        {
          extends: true,
          test: {
            name: 'unit',
            environment: 'node',
            include: ['tests/**/*.test.ts'],
            exclude: ['tests/**/*.component.test.ts', 'tests/**/*.browser.test.ts'],
          },
        },
        {
          extends: true,
          test: {
            name: 'component',
            environment: 'jsdom',
            include: ['tests/**/*.component.test.ts'],
            setupFiles: ['./tests/setup.ts'],
          },
        },
        {
          extends: true,
          test: {
            name: 'browser',
            include: browserTestEnabled ? ['tests/browser/*.browser.test.ts'] : [],
            testTimeout: 30_000,
            hookTimeout: 30_000,
            browser: {
              enabled: browserTestEnabled,
              provider: playwright(),
              instances: [
                {
                  browser: 'chromium',
                  viewport: { width: 1280, height: 814 },
                },
              ],
              screenshotFailures: true,
              trace: 'retain-on-failure',
            },
          },
        },
      ],
    },
  }),
);
