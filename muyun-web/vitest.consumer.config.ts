import { defineConfig } from 'vitest/config';

// Only verify:consumer runs this project, after installing and rebuilding the tarball.
export default defineConfig({
  test: {
    projects: [
      {
        test: {
          name: 'consumer-node',
          environment: 'node',
          include: ['tests/consumer/packaged/**/*.test.ts'],
          exclude: ['tests/consumer/packaged/**/*.component.test.ts'],
        },
      },
      {
        test: {
          name: 'consumer-component',
          environment: 'jsdom',
          include: ['tests/consumer/packaged/**/*.component.test.ts'],
          setupFiles: ['./tests/setup.ts'],
        },
      },
    ],
  },
});
