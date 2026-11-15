export type SourceMode = 'file' | 'url'

export interface RecordedFormValues {
  title: string
  mode: SourceMode
  file: { type: string } | null
  videoUrl: string
}

export type RecordedFormErrors = Partial<Record<'title' | 'file' | 'videoUrl', string>>

export function isHttpUrl(value: string): boolean {
  try {
    const u = new URL(value)
    return (u.protocol === 'http:' || u.protocol === 'https:') && u.hostname !== ''
  } catch {
    return false
  }
}

/** Client-side mirror of the server rules (the server remains the authority). */
export function validateRecordedForm(v: RecordedFormValues): RecordedFormErrors {
  const errors: RecordedFormErrors = {}
  if (v.title.trim() === '') errors.title = '제목을 입력해 주세요.'
  else if (v.title.trim().length > 200) errors.title = '제목은 200자 이하여야 합니다.'

  if (v.mode === 'file') {
    if (!v.file) errors.file = '영상 파일을 선택해 주세요.'
    else if (!v.file.type.startsWith('video/')) errors.file = '영상 파일(video/*)만 업로드할 수 있습니다.'
  } else if (v.videoUrl.trim() === '') {
    errors.videoUrl = '영상 URL을 입력해 주세요.'
  } else if (!isHttpUrl(v.videoUrl.trim())) {
    errors.videoUrl = 'http 또는 https 주소를 입력해 주세요.'
  }
  return errors
}
