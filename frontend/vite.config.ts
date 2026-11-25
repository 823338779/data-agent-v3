import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 本地开发时将健康检查转发到 V3 后端，浏览器只访问前端所在源。
export default defineConfig({
  plugins: [react()],
  server: {
    host: '127.0.0.1',
    port: 5173,
    proxy: {
      '/actuator/health': 'http://127.0.0.1:8083',
    },
  },
})
