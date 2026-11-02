import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vite';
import elmPlugin from 'vite-plugin-elm';

// Use the project's compiler even when Vite is started without `bun run` (which puts it on PATH).
const pathToElm = fileURLToPath(new URL('./node_modules/.bin/elm', import.meta.url));

export default defineConfig({
  plugins: [elmPlugin({ nodeElmCompilerOptions: { pathToElm } })],
  server: {
    port: 4500,
    // Same contract as the Angular proxy: /api/accounts/x -> http://localhost:8080/accounts/x
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, ''),
      },
    },
  },
});
