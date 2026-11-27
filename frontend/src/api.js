import axios from 'axios'

// 개발 서버의 /apis 요청은 Vite 프록시를 통해 Spring Boot로 전달된다.
const api = axios.create({
  baseURL: '/apis/v3',
  withCredentials: true,
})

export async function registerUser({ id, password, email, name }) {
  const response = await api.post('/auth/register', {
    id,
    password,
    email,
    name,
    role: 'USER',
  })
  return response.data
}

export async function loginUser({ id, password }) {
  const response = await api.post('/auth/login', { id, password })
  return response.data
}

export async function readyPayment(orderId, provider = 'TOSS') {
  const response = await api.post('/payments/ready', {
    provider,
    orderId,
  })
  return response.data.data
}

export async function retryPayment(paymentId) {
  const response = await api.post(`/payments/${paymentId}/retry`)
  return response.data.data
}

export async function approvePayment({ paymentKey, externalPaymentId, orderId, amount,
  provider = 'TOSS', clientId, authToken, signature }) {
  const response = await api.post('/payments', {
    provider,
    externalPaymentId: externalPaymentId ?? paymentKey,
    orderId,
    amount,
    ...(provider === 'NICE' ? { clientId, authToken, signature } : {}),
  })
  return response.data.data
}

export async function loadPayments(page = 0) {
  const response = await api.get('/payments', { params: { page } })
  return response.data.data
}

export async function loadPayment(paymentId) {
  const response = await api.get(`/payments/${paymentId}`)
  return response.data.data
}

export function apiError(error) {
  return error.response?.data?.message ?? error.message ?? '요청에 실패했습니다.'
}
