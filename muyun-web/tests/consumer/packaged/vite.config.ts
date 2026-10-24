import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

export default defineConfig({
  plugins: [vue()],
  build: {
    outDir: 'contract-dist',
    lib: {
      entry: 'src/consumer-contract.ts',
      name: 'MuyunConsumerContract',
      formats: ['iife'],
      fileName: () => 'consumer-contract.js',
    },
    rollupOptions: {
      external: ['vue'],
      output: { globals: { vue: 'Vue' } },
    },
  },
});
