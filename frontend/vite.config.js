import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// NICEPAY가 결제창 인증 결과를 POST하면 프런트 화면으로 전달한다.
function niceReturn() {
  return {
    name: 'nice-return',
    configureServer(server) {
      server.middlewares.use('/nice/return', async (request, response, next) => {
        if (request.method !== 'POST') return next()

        const chunks = []
        for await (const chunk of request) chunks.push(chunk)
        const values = new URLSearchParams(Buffer.concat(chunks).toString('utf8'))

        if (values.get('authResultCode') !== '0000') {
          const failure = new URLSearchParams({
            code: values.get('authResultCode') ?? 'NICE_AUTH_FAILED',
            message: values.get('authResultMsg') ?? '결제창 인증에 실패했습니다.',
          })
          response.writeHead(303, { Location: `/fail?${failure}` })
          response.end()
          return
        }

        // 인증 토큰은 서버 조회 URL에 담지 않고 fragment로 React 화면에 전달한다.
        const result = new URLSearchParams()
        for (const name of ['tid', 'orderId', 'amount', 'clientId', 'authToken', 'signature']) {
          result.set(name, values.get(name) ?? '')
        }
        response.writeHead(303, { Location: `/nice/success#${result}` })
        response.end()
      })
    },
  }
}

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), niceReturn()],
  server: {
    proxy: {
      '/apis': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
