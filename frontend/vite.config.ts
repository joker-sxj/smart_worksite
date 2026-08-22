import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import AutoImport from 'unplugin-auto-import/vite';
import Components from 'unplugin-vue-components/vite';
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers';

const elementPlusResolver = ElementPlusResolver({ importStyle: false });

export default defineConfig({
  plugins: [
    vue(),
    AutoImport({
      dts: false,
      resolvers: [elementPlusResolver]
    }),
    Components({
      dirs: [],
      dts: false,
      resolvers: [elementPlusResolver]
    })
  ],
  build: {
    chunkSizeWarningLimit: 650,
    rolldownOptions: {
      onLog(level, log, handler) {
        const text = String(log.message || log);
        if (log.code === 'INVALID_ANNOTATION' && text.includes('@vueuse/core')) return;
        handler(level, log);
      },
      output: {
        manualChunks(id) {
          if (id.includes('node_modules/vue') || id.includes('node_modules/vue-router') || id.includes('node_modules/pinia')) return 'vue';
          if (id.includes('node_modules/element-plus') || id.includes('node_modules/@element-plus')) return 'element-plus';
          if (id.includes('node_modules/axios')) return 'axios';
          if (id.includes('node_modules')) return 'vendor';
          return undefined;
        }
      }
    }
  },
  server: {
    host: '0.0.0.0',
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
});
