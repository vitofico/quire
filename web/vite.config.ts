import { statSync } from 'node:fs'
import { resolve, sep } from 'node:path'
import { defineConfig, type Plugin } from 'vite'
import react from '@vitejs/plugin-react'

function previewPublicAssets(): Plugin {
  return {
    name: 'quire-preview-public-assets',
    configurePreviewServer(server) {
      const output = resolve(server.config.root, server.config.build.outDir)
      server.middlewares.use((request, response, next) => {
        const path = request.url?.split('?')[0] ?? ''
        // Playwright exercises actual origin loss so browsers still dispatch to the worker.
        if (process.env.QUIRE_E2E_PREVIEW === '1' &&
            request.headers.cookie?.split(';').some(value => value.trim() === 'quire-test-offline=1')) {
          request.socket.destroy()
          return
        }
        // Mirror the production provenance marker for real build files.
        if (/^\/quire\/(?:assets\/[A-Za-z0-9_.-]+|index\.html|icon-(?:192|512)\.png|manifest\.webmanifest|version\.json|sw\.js)$/.test(path)) {
          const file = resolve(output, path.slice('/quire/'.length))
          if (file.startsWith(output + sep) && statSync(file, { throwIfNoEntry: false })?.isFile()) {
            response.setHeader('X-Quire-Public-Asset', '1')
          }
        }
        next()
      })
    },
  }
}

export default defineConfig({
  base: '/quire/',
  plugins: [react(), previewPublicAssets()],
  server: {
    proxy: Object.fromEntries(
      [
        '/auth/',
        '/sync/',
        '/library/',
        '/ai/',
        '/details/',
        '/catalogs/',
        '/convert/',
        '/opds',
        '/health',
        '/quire/v1/',
      ].map((path) => [path, 'http://127.0.0.1:8000']),
    ),
  },
})
