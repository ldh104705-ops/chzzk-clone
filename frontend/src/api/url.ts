/** Joins the API base URL (may be empty = same origin) with a path or passes absolute URLs through. */
export function buildUrl(path: string, base: string = import.meta.env.VITE_API_BASE_URL ?? ''): string {
  if (/^https?:\/\//i.test(path)) return path
  const trimmedBase = base.replace(/\/+$/, '')
  const normalizedPath = path.startsWith('/') ? path : `/${path}`
  return `${trimmedBase}${normalizedPath}`
}
