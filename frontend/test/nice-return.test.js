import { test } from 'node:test'
import assert from 'node:assert/strict'
import { Readable } from 'node:stream'
import config from '../vite.config.js'

/** 결제창 POST를 받는 프런트 개발 서버 미들웨어를 직접 실행한다. */
async function result(body) {
  let handler
  const plugin = config.plugins.find((item) => item.name === 'nice-return')
  plugin.configureServer({ middlewares: { use: (_path, callback) => { handler = callback } } })

  const request = Readable.from([Buffer.from(body)])
  request.method = 'POST'
  const response = {
    status: null,
    headers: null,
    writeHead(status, headers) { this.status = status; this.headers = headers },
    end() {},
  }
  await handler(request, response, () => {})
  return response
}

test('NICEPAY 인증 결과 POST를 React 결과 화면에 전달한다', async () => {
  const values = new URLSearchParams({ authResultCode: '0000', tid: 'tid-001',
    orderId: 'order-001', amount: '1000', clientId: 'client-001',
    authToken: 'token-001', signature: 'signature-001' })
  const response = await result(values.toString())

  assert.equal(response.status, 303)
  assert.match(response.headers.Location, /^\/nice\/success#tid=tid-001/)
  assert.match(response.headers.Location, /authToken=token-001/)
})

test('NICEPAY 인증 실패는 승인 화면으로 보내지 않는다', async () => {
  const response = await result('authResultCode=3001&authResultMsg=cancelled')

  assert.equal(response.status, 303)
  assert.match(response.headers.Location, /^\/fail\?code=3001/)
})
