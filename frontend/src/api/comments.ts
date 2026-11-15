import { apiRequest, jsonBody } from './http'

export interface Comment {
  id: number
  broadcastId: number
  content: string
  authorId: number
  authorNickname: string
  createdAt: string
  updatedAt: string
}

export interface CommentPage {
  items: Comment[]
  page: number
  size: number
  totalElements: number
  hasNext: boolean
}

export const MAX_COMMENT_LENGTH = 500

export async function listComments(broadcastId: number | string, page = 0, size = 30): Promise<CommentPage> {
  const res = await apiRequest(`/api/broadcasts/${broadcastId}/comments?page=${page}&size=${size}`)
  return res.json()
}

export async function createComment(broadcastId: number | string, content: string): Promise<Comment> {
  const res = await apiRequest(`/api/broadcasts/${broadcastId}/comments`, { method: 'POST', ...jsonBody({ content }) })
  return res.json()
}

export async function updateComment(id: number, content: string): Promise<Comment> {
  const res = await apiRequest(`/api/comments/${id}`, { method: 'PATCH', ...jsonBody({ content }) })
  return res.json()
}

export async function deleteComment(id: number): Promise<void> {
  await apiRequest(`/api/comments/${id}`, { method: 'DELETE' })
}
