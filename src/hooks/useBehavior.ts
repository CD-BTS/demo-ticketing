import { useCallback } from 'react'
import { sendBehaviorEvent } from '../api/bts'
import type { BehaviorEventType } from '../api/types'
import { useAuth } from '../auth/AuthContext'

const DEVICE_KEY = 'fds.deviceId'

// 기기 변경 빈도 feature용 — 브라우저 단위 임시 기기 ID
function getDeviceId(): string {
  try {
    let id = localStorage.getItem(DEVICE_KEY)
    if (!id) {
      id = `dev_${crypto.randomUUID()}`
      localStorage.setItem(DEVICE_KEY, id)
    }
    return id
  } catch {
    return 'dev_unknown'
  }
}

/** 각 예매 단계의 사용자 행동을 Event API로 전달 (실패해도 화면 흐름은 막지 않는다) */
export function useBehavior() {
  const { userId } = useAuth()
  return useCallback(
    (eventType: BehaviorEventType, metadata?: Record<string, unknown>) => {
      if (!userId) return
      sendBehaviorEvent({
        userId,
        eventType,
        occurredAt: new Date().toISOString(),
        deviceId: getDeviceId(),
        metadata,
      }).catch((e) => console.warn('[event failed]', eventType, e))
    },
    [userId],
  )
}
