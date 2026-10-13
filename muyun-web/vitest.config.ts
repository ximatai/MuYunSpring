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
      // Bound Vue compilation and DOM-environment memory during full verification.
      maxWorkers: 4,
      projects: [
        {
          extends: true,

          test: {
            name: 'browser',
            // Real mouse gestures and animations need predictable frame scheduling.
            fileParallelism: false,
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
      ],
    },
  }),
);
