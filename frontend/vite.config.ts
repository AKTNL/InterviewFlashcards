import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import { cp, readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { resolve, sep } from 'node:path'

// Serve PDF.js resources locally in development and copy the same version on build.
const pdfRoot = fileURLToPath(new URL('./node_modules/pdfjs-dist/', import.meta.url))
const resourceFolders = ['cmaps', 'standard_fonts', 'wasm']

export default defineConfig({
  plugins: [vue(), {
    name: 'pdfjs-resources',
    configureServer(server) {
      server.middlewares.use('/pdfjs', async (req, res, next) => {
        const relative = (req.url ?? '').split('?')[0]!.replace(/^\//, '')
        if (!resourceFolders.includes(relative.split('/')[0]!)) return next()
        const target = resolve(pdfRoot, relative)
        if (!target.startsWith(pdfRoot.endsWith(sep) ? pdfRoot : pdfRoot + sep)) return next()
        try { const bytes = await readFile(target); res.setHeader('Content-Type', relative.endsWith('.wasm') ? 'application/wasm' : 'application/octet-stream'); res.end(bytes) }
        catch { next() }
      })
    },
    async writeBundle(options) {
      for (const folder of resourceFolders) await cp(resolve(pdfRoot, folder), resolve(options.dir ?? 'dist', 'pdfjs', folder), { recursive: true })
    },
  }],
  server: {
    port: 5173,
    strictPort: true,
    proxy: { '/api': { target: 'http://127.0.0.1:8080' } },
  },
  test: {
    environment: 'jsdom',
    clearMocks: true,
    restoreMocks: true,
  },
})
