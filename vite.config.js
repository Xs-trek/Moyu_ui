import { defineConfig } from 'vite';
import { resolve } from 'node:path';

export default defineConfig({
  base: './',
  build: {
    target: 'es2018',
    cssTarget: 'chrome83',
    rollupOptions: {
      input: {
        index: resolve(import.meta.dirname, 'index.html'),
        preview: resolve(import.meta.dirname, 'preview.html')
      }
    }
  }
});
