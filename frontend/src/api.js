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

export async function readyPayment(orderId) {
  const response = await api.post('/payments/ready', {
    provider: 'TOSS',
    orderId,
  })
  return response.data.data
}

export async function approvePayment({ paymentKey, orderId, amount }) {
  const response = await api.post('/payments', {
    provider: 'TOSS',
    externalPaymentId: paymentKey,
    orderId,
    amount,
  })
  return response.data.data
}

export async function loadClientKey() {
  const response = await api.get('/payments/toss/client-key')
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
