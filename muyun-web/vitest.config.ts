import { availableParallelism } from 'node:os';
import { defineConfig, mergeConfig } from 'vitest/config';
import { playwright } from '@vitest/browser-playwright';
import {
  treeGesture,
  treeMove,
  treeRelease,
  treeReducedMotion,
  treeScrollGesture,
  treeWheel,
} from './tests/vue-ui-antdv/browserCommands.ts';
import { createViteConfig } from './vite.config.ts';
import { workflowCanvasDrag } from './tests/platform-components/workflowCanvasBrowserCommands.ts';

export default mergeConfig(
  createViteConfig('test'),
  defineConfig({
    server: {
      proxy: process.env.MUYUN_TREE_SERVICE_URL
        ? {
            '/api': {
              target: process.env.MUYUN_TREE_SERVICE_URL,
              changeOrigin: true,
              configure: (proxy) => proxy.on('proxyReq', (request) => request.removeHeader('origin')),
            },
          }
        : {},
    },
    define: {
      __TREE_SERVICE_ENABLED__: Boolean(
        process.env.MUYUN_TREE_SERVICE_URL && process.env.MUYUN_TREE_SERVICE_TOKEN,
      ),
      __TREE_SERVICE_TOKEN__: JSON.stringify(process.env.MUYUN_TREE_SERVICE_TOKEN ?? ''),
    },

    test: {
      // Keep file isolation while avoiding a separate Node process for every test file.
      pool: 'threads',
      // Bound Vue compilation and DOM-environment memory during full verification.
      maxWorkers: 4,
      projects: [
        {
          extends: true,

          test: {
            name: 'browser',
            // Run after Node/jsdom with a smaller worker budget and isolated DOM and gesture state.
            sequence: { groupOrder: 1 },
            fileParallelism: true,
            maxWorkers: Math.min(2, availableParallelism()),
            isolate: true,
            include: ['tests/**/*.browser.test.ts'],
            setupFiles: ['./tests/setup.ts'],
            browser: {
              screenshotDirectory: './test-results/browser',
              enabled: true,
              headless: true,
              provider: playwright(),
              instances: [{ browser: 'chromium' }],
              commands: {
                workflowCanvasDrag,
                treeGesture,
                treeMove,
                treeRelease,
                treeReducedMotion,
                treeScrollGesture,
                treeWheel,
              },
            },
          },
        },
        {
          extends: true,
          test: {
            name: 'unit',
            environment: 'node',
            include: ['tests/**/*.test.ts'],
            exclude: [
              'tests/**/*.component.test.ts',
              'tests/**/*.browser.test.ts',
              'tests/consumer/packaged/**',
            ],
          },
        },
        {
          extends: true,
          test: {
            name: 'component',
            environment: 'jsdom',
            include: ['tests/**/*.component.test.ts'],
            exclude: ['tests/consumer/packaged/**'],
            setupFiles: ['./tests/setup.ts'],
          },
        },
      ],
    },
  }),
);
