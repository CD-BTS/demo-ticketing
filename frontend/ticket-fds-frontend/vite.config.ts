import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 개발 환경에서 /api 요청을 Spring Boot(8080)로 프록시 (CORS 회피)
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
})
