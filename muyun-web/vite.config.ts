import { fileURLToPath, URL } from 'node:url';
import vue from '@vitejs/plugin-vue';
import { defineConfig, loadEnv } from 'vite';

export function createViteConfig(mode = 'development') {
  const environment = loadEnv(mode, process.cwd(), '');
  return {
    // The workbench mount point is deployment configuration, not page metadata.
    // Keep the default isolated from backend API paths while allowing a host to
    // mount the same artifact at a different prefix.
    base: normalizeWorkbenchBase(environment.VITE_MUYUN_WEB_BASE),
    plugins: [vue()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
        '@muyun/vue-ui-antdv/styles.css': fileURLToPath(
          new URL('./src/vue-ui-antdv/styles.css', import.meta.url),
        ),
        '@muyun/web-contracts': fileURLToPath(new URL('./src/web-contracts/index.ts', import.meta.url)),
        '@muyun/web-core': fileURLToPath(new URL('./src/web-core/index.ts', import.meta.url)),
        '@muyun/vue-ui-antdv': fileURLToPath(new URL('./src/vue-ui-antdv/index.ts', import.meta.url)),
        '@muyun/dynamic-page-runtime': fileURLToPath(
          new URL('./src/dynamic-page-runtime/index.ts', import.meta.url),
        ),
        '@muyun/platform-components': fileURLToPath(
          new URL('./src/platform-components/index.ts', import.meta.url),
        ),
        '@muyun/platform-workbench': fileURLToPath(
          new URL('./src/platform-workbench/index.ts', import.meta.url),
        ),
      },
    },
    build: {
      sourcemap: false,
    },
  };
}

export default defineConfig(({ mode }) => createViteConfig(mode));

export function normalizeWorkbenchBase(value: string | undefined): string {
  const trimmed = value?.trim() || '/app';
  return `/${trimmed.replace(/^\/+|\/+$/g, '')}/`.replace(/^\/\/+/g, '/');
}
