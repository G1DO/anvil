import { defineConfig } from 'vitest/config';
import type { Plugin } from 'vite';

/**
 * HtmlUnit 5.7 does not execute ES modules for this page. Vite emits a module
 * script by default; in production, downgrade it to a classic deferred script.
 * `defer` keeps execution after the document body has been parsed, so React can
 * mount into #root without a DOMContentLoaded listener.
 *
 * Dev mode must keep the module script: `/src/main.tsx` only works as a module.
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

export default defineConfig(({ command }) => ({
  base: './',
  plugins: [classicDeferredScript(command === 'build')],
  server: {
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
      '/audit': { target: 'http://localhost:8080', changeOrigin: true },
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
