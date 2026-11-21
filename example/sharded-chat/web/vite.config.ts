import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

// The bundle goes into Spring's static folder, which serves it at /. The dev server proxies /api to the control app.
// public/.gitkeep is copied back after the folder is emptied, so it stays in git.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  build: {
    outDir: '../control/src/main/resources/static',
    emptyOutDir: true,
  },
  server: {
    proxy: { '/api': 'http://localhost:8080' },
  },
});
