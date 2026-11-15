export type Facing = 'user' | 'environment'

export interface MediaEnv {
  isSecureContext: boolean
  mediaDevices?: unknown
}

export const INSECURE_CONTEXT_MESSAGE =
  '휴대폰 브라우저는 HTTPS에서만 카메라를 허용합니다. https:// 주소로 접속해 주세요. ' +
  '(개발 중에는 `npm run dev:https` 또는 cloudflared 같은 HTTPS 터널을 사용하세요. localhost만 HTTP에서도 허용됩니다.)'

export const NO_MEDIA_API_MESSAGE =
  '이 브라우저는 카메라/마이크 API(getUserMedia)를 지원하지 않습니다. 최신 Chrome(Android) 또는 Safari(iOS)를 사용해 주세요.'

/** Returns a Korean explanation when the page cannot use the camera at all, otherwise null. */
export function checkMediaSupport(env: MediaEnv): string | null {
  if (!env.isSecureContext) return INSECURE_CONTEXT_MESSAGE
  if (!env.mediaDevices) return NO_MEDIA_API_MESSAGE
  return null
}

/** Maps a getUserMedia failure to a message the broadcaster can act on. */
export function describeMediaError(error: unknown): string {
  const name = error instanceof Error ? error.name : ''
  switch (name) {
    case 'NotAllowedError':
    case 'SecurityError':
    case 'PermissionDeniedError':
      return (
        '카메라/마이크 권한이 거부되었습니다. 주소창 옆 사이트 설정에서 카메라와 마이크를 "허용"으로 바꾼 뒤 ' +
        '페이지를 새로고침해 주세요. (iOS는 설정 > Safari > 카메라/마이크도 확인하세요.)'
      )
    case 'NotFoundError':
    case 'DevicesNotFoundError':
      return '카메라 또는 마이크 장치를 찾을 수 없습니다.'
    case 'NotReadableError':
    case 'TrackStartError':
      return '카메라/마이크를 사용할 수 없습니다. 다른 앱이나 탭이 사용 중인지 확인해 주세요.'
    case 'OverconstrainedError':
      return '요청한 카메라(전면/후면)를 사용할 수 없습니다.'
    default:
      return `카메라/마이크를 시작하지 못했습니다${error instanceof Error && error.message ? `: ${error.message}` : '.'}`
  }
}

export function otherFacing(facing: Facing): Facing {
  return facing === 'user' ? 'environment' : 'user'
}
