import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { fileURLToPath, URL } from 'node:url';

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) }
  },
  build: {
    // 产物直接输出到 Spring 静态目录，由后端统一托管（无需单独部署前端）
    outDir: '../src/main/resources/static',
    emptyOutDir: true
  },
  server: {
    port: 5173,
    proxy: {
      // 开发期把 /api 代理到本地 Spring 服务
      '/api': { target: 'http://localhost:20810', changeOrigin: true }
    }
  }
});
