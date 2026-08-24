import { defineConfig } from 'vite';
import { realpathSync } from 'node:fs';
import { resolve } from 'node:path';

const projectRoot = realpathSync(process.cwd());

export default defineConfig({
  root: projectRoot,
  base: './',
  build: {
    target: 'es2018',
    cssTarget: 'chrome83',
    modulePreload: { polyfill: false },
    rollupOptions: {
      input: {
        index: resolve(projectRoot, 'index.html'),
        preview: resolve(projectRoot, 'preview.html'),
        phone: resolve(projectRoot, 'phone.html')
      }
    }
  }
});
