import { defineConfig, mergeConfig } from 'vitest/config';
import viteConfig from './vite.config';

export default mergeConfig(
  viteConfig,
  defineConfig({
    test: {
      projects: [
        {
          extends: true,
          test: {
            name: 'unit',
            environment: 'node',
            include: ['tests/**/*.test.ts'],
            exclude: ['tests/**/*.component.test.ts'],
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
