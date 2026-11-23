import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The Mini Redis web gateway runs on :8081. The UI calls /api/* and Vite forwards it (no CORS setup needed).
const proxy = {
  '/api': {
    target: process.env.BACKEND_URL || 'http://127.0.0.1:8081',
    changeOrigin: true,
  },
};

export default defineConfig({
  plugins: [react()],
  server: { port: 5173, proxy },
  preview: { port: 4173, proxy },
});
