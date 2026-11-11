import { defineConfig } from 'vitest/config';

// Only verify:consumer runs this project, after installing and rebuilding the tarball.
export default defineConfig({
  test: {
    reporters: process.env.MUYUN_TEST_REPORT_DISABLED === '1' ? ['default'] : ['default', 'json'],
    outputFile:
      process.env.MUYUN_TEST_REPORT_DISABLED === '1'
        ? undefined
        : {
            json:
              process.env.MUYUN_TEST_REPORT_PATH ?? `./test-results/vitest/${Date.now()}-${process.pid}.json`,
          },
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
