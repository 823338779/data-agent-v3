const SESSIONS = '/api/sessions'
const EVENT_PREFIX = 'event:'
const DATA_PREFIX = 'data:'
const POLL_MS = 500
const CONNECTION_LOST = '连接中断，正在恢复会话…'

/** 调用和结果分别对应模型协议中的助手消息与工具消息。 */
export type ToolCall = { id: string; name: string; arguments: string }
export type Message = {
  role: 'user' | 'assistant' | 'tool'
  content: string
  calls: ToolCall[]
  toolCallId?: string
  success?: boolean
}
export type SteerInput = { clientMessageId: string; message: string }
export type SteerReceipt = SteerInput & { runId: string; runStatus: string; messageSeq: number | null }
export type Run = {
  id: string; status: string; failure?: string; messages: Message[]
  canSteer?: boolean; steerCloseReason?: string | null; steers?: SteerReceipt[]
  eventSeq?: number
}
export type Snapshot = { sessionId: string; activeRunId: string | null; runs: Run[] }
/** 最近会话的标识、首条提问摘要和服务端最近活动时间。 */
export type RecentSession = { id: string; title: string; updatedAt: string }
export type RunEvent = {
  type: string; runId: string; seq: number; messageSeq: number
  text: string | null; call: ToolCall | null; result: Message | null
  clientMessageId?: string | null; canSteer?: boolean | null; steerCloseReason?: string | null
}

async function checked(response: Response) {
  if (response.ok) return response
  if (response.status === 409) throw new Error('会话正在运行，请稍后发送。')
  if (response.status === 404) throw new Error('找不到这个会话。')
  if (response.status >= 500) throw new TemporaryServerError(`服务暂时不可用（HTTP ${response.status}）`)
  throw new Error(`请求失败（HTTP ${response.status}）`)
}

class TemporaryServerError extends Error {}

/** 只有确定拒绝才能释放一笔未确认的补充，网络故障和 5xx 保留原提交键。 */
export class SteerRejection extends Error {
  readonly code?: string
  readonly reason?: string | null

  constructor(message: string, code?: string, reason?: string | null) {
    super(message)
    this.code = code
    this.reason = reason
  }
}

const STEER_REJECTION_TEXT: Record<string, string> = {
  ID_REUSED: '提交 ID 已使用，请核对原补充。',
  STEER_CLOSED: '这一轮已关闭补充，草稿仍可手动发送。',
  STEER_LIMIT: '补充超过本轮的条数或字节上限。',
}

export async function submitSteer(sessionId: string, runId: string, input: SteerInput,
  signal?: AbortSignal): Promise<SteerReceipt> {
  const path = `${SESSIONS}/${encodeURIComponent(sessionId)}/runs/${encodeURIComponent(runId)}/steers`
  const response = await fetch(path, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(input), signal,
  })
  if (!response.ok) {
    if (response.status >= 500 || response.status === 408 || response.status === 429) {
      throw new TemporaryServerError(`补充接收待确认（HTTP ${response.status}）`)
    }
    const error = await response.json().catch(() => ({}))
    const text = STEER_REJECTION_TEXT[error.code] ?? `补充未接收（HTTP ${response.status}）`
    throw new SteerRejection(text, error.code, error.reason)
  }
  const receipt: SteerReceipt = await response.json()
  if (receipt.runId !== runId || receipt.clientMessageId !== input.clientMessageId || receipt.message !== input.message) {
    throw new Error('无法确认补充回执对应本次提交。')
  }
  return receipt
}

/** 侧栏读取服务端保存的最近会话，打开时再加载完整消息。 */
export async function recentSessions(signal?: AbortSignal): Promise<RecentSession[]> {
  return (await checked(await fetch(SESSIONS, { signal }))).json()
}

export async function createSession(): Promise<string> {
  const response = await checked(await fetch(SESSIONS, { method: 'POST' }))
  return (await response.json()).sessionId
}

async function snapshot(id: string, signal?: AbortSignal): Promise<Snapshot> {
  return (await checked(await fetch(`${SESSIONS}/${encodeURIComponent(id)}`, { signal }))).json()
}

async function run(id: string, message: string, onEvent: (event: RunEvent) => void, signal?: AbortSignal) {
  const response = await checked(await fetch(`${SESSIONS}/${encodeURIComponent(id)}/runs`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ message }), signal,
  }))
  if (!response.body) throw new Error('浏览器未收到事件流。')
  await readEvents(response.body, onEvent)
}

/** 恢复只读同一会话，即使快照请求暂时断线也不重复提交。 */
export async function restoreSession(id: string, receive: (state: Snapshot) => void,
  onRetry: (status: string) => void, signal?: AbortSignal) {
  let delay = POLL_MS
  for (;;) {
    signal?.throwIfAborted()
    try {
      const state = await snapshot(id, signal)
      receive(state)
      if (!state.activeRunId) return
      delay = POLL_MS
    } catch (cause) {
      if (signal?.aborted || !(cause instanceof TypeError || cause instanceof TemporaryServerError)) throw cause
      onRetry(CONNECTION_LOST)
      delay = Math.min(delay * 2, 5000)
    }
    await new Promise(resolve => setTimeout(resolve, delay))
  }
}

export async function runAndRestore(id: string, message: string, onEvent: (event: RunEvent) => void,
  receive: (state: Snapshot) => void, onRetry: (status: string) => void, signal?: AbortSignal) {
  try {
    await run(id, message, onEvent, signal)
  } finally {
    if (!signal?.aborted) await restoreSession(id, receive, onRetry, signal)
  }
}

/** 使用增量解码器保留跨网络块的 UTF-8 字节、行和 SSE 帧。 */
export async function readEvents(stream: ReadableStream<Uint8Array>, onEvent: (event: RunEvent) => void) {
  const reader = stream.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  try {
    for (;;) {
      const { done, value } = await reader.read()
      buffer += decoder.decode(value, { stream: !done })
      let boundary = /\r?\n\r?\n/.exec(buffer)
      while (boundary) {
        const lines = buffer.slice(0, boundary.index).split(/\r?\n/)
        buffer = buffer.slice(boundary.index + boundary[0].length)
        const name = lines.find(line => line.startsWith(EVENT_PREFIX))?.slice(EVENT_PREFIX.length).trim()
        const data = lines.filter(line => line.startsWith(DATA_PREFIX)).map(line => line.slice(DATA_PREFIX.length)).join('\n')
        if (name && data) onEvent({ ...JSON.parse(data), type: name })
        boundary = /\r?\n\r?\n/.exec(buffer)
      }
      if (done) break
    }
  } finally {
    reader.releaseLock()
  }
}
