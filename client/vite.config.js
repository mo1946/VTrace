import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

// Vite 配置参考：https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const proxy = {
    '/api': {
      target: env.PYTHON_API_TARGET || 'http://127.0.0.1:9090',
      changeOrigin: true,
      rewrite: path => path.replace(/^\/api(?=\/)/, '')
    },
    '/business-api': {
      target: env.BUSINESS_API_TARGET || 'http://127.0.0.1:8080',
      changeOrigin: true,
      rewrite: path => path.replace(/^\/business-api(?=\/)/, '')
    }
  }
  return { plugins: [vue()], server: { proxy }, preview: { proxy } }
})
