import { defineConfig } from 'vitest/config';

// Only verify:consumer runs this project, after installing and rebuilding the tarball.
export default defineConfig({
  test: {
    environment: 'jsdom',
    include: ['tests/consumer/packaged/**/*.component.test.ts'],
    setupFiles: ['./tests/setup.ts'],
  },
});
