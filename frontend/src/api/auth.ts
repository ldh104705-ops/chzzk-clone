import { apiRequest, jsonBody } from './http'

export interface User {
  id: number
  username: string
  nickname: string
}

export interface LoginResult {
  token: string
  user: User
}

export async function signup(username: string, password: string, nickname: string): Promise<User> {
  const res = await apiRequest('/api/auth/signup', { method: 'POST', ...jsonBody({ username, password, nickname }) })
  return res.json()
}

export async function login(username: string, password: string): Promise<LoginResult> {
  const res = await apiRequest('/api/auth/login', { method: 'POST', ...jsonBody({ username, password }) })
  return res.json()
}

/** Tells the server to stop accepting this token (the caller still discards it locally). */
export async function logoutOnServer(): Promise<void> {
  await apiRequest('/api/auth/logout', { method: 'POST' })
}

export async function fetchMe(): Promise<User> {
  const res = await apiRequest('/api/auth/me')
  return res.json()
}
