import react from '@vitejs/plugin-react';
import type { Plugin } from 'vite';
import { defineConfig } from 'vitest/config';

/**
 * HtmlUnit 5.7 does not execute ES modules for this page. Vite emits a module
 * script by default; in production, downgrade it to a classic deferred script.
 * Dev mode keeps the module script because `/src/main.tsx` is only served as a
 * module by the Vite dev server.
 */
function classicDeferredScript(enabled: boolean): Plugin {
  return {
    name: 'anvil-classic-deferred-script',
    enforce: 'post',
    transformIndexHtml(html) {
      if (!enabled) {
        return html;
      }
      return html.replace(/<script\b([^>]*)>/g, (_tag, attributes: string) => {
        const classic = attributes
          .replace(/\s+type="module"/, ' defer')
          // `async` would override defer and could execute before #root exists.
          .replace(/\s+async(?=\s|$)/, '');
        return `<script${classic}>`;
      });
    },
  };
}

// https://vite.dev/config/
export default defineConfig(({ command }) => ({
  base: './',
  plugins: [react(), classicDeferredScript(command === 'build')],
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
      '/audit': 'http://localhost:8080',
    },
  },
  build: {
    target: 'es2020',
    outDir: '../src/main/resources/static/console',
    emptyOutDir: true,
    modulePreload: false,
    assetsInlineLimit: 0,
    rollupOptions: {
      output: {
        format: 'iife',
        entryFileNames: 'assets/app.js',
        assetFileNames: 'assets/[name][extname]',
        inlineDynamicImports: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    restoreMocks: true,
    css: false,
  },
}));
