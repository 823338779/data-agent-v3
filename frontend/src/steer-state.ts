import type { Message, Run, RunEvent, SteerReceipt } from './chat-api'

const RUNNING = 'RUNNING', FAILED = 'FAILED', SUCCEEDED = 'SUCCEEDED'
const LIMIT = 'LIMIT', TERMINAL = 'TERMINAL'
const ASSISTANT = 'assistant', USER = 'user', TOOL = 'tool'
const STARTED = 'run.started', COMPLETED = 'run.completed', FAILURE = 'run.failed'
const STEER_GATE = 'run.steer_gate', STEER_INCORPORATED = 'run.steer_incorporated'
const REPLY_COMMITTED = 'run.reply_committed', DELTA = 'text.delta'
const TOOL_STARTED = 'tool.started', TOOL_FINISHED = 'tool.finished'

export function deriveComposerMode(activeRunId: string | null, activeRun: Run | undefined,
  busy: boolean, unresolved: boolean): 'send' | 'steer' | 'disabled' {
  if (unresolved) return 'disabled'
  if (activeRunId) return activeRun?.id === activeRunId && activeRun?.status === RUNNING && activeRun?.canSteer === true
    ? 'steer' : 'disabled'
  return busy ? 'disabled' : 'send'
}

export function deriveSteerDisplay(receipt: SteerReceipt, runStatus: string): 'message' | 'waiting' | 'unused' {
  if (receipt.messageSeq != null) return 'message'
  return runStatus === RUNNING && receipt.runStatus === RUNNING ? 'waiting' : 'unused'
}

export function mergeSteerReceipt(previous: SteerReceipt | undefined, incoming: SteerReceipt): SteerReceipt {
  if (!previous || previous.runId !== incoming.runId || previous.clientMessageId !== incoming.clientMessageId) {
    return incoming
  }
  return { ...incoming, messageSeq: previous.messageSeq ?? incoming.messageSeq,
    runStatus: previous.runStatus === RUNNING ? incoming.runStatus : previous.runStatus }
}

function mergeReceipts(previous: SteerReceipt[] = [], incoming: SteerReceipt[] = []) {
  const entries = new Map(previous.map(item => [item.clientMessageId, item]))
  for (const receipt of incoming) {
    entries.set(receipt.clientMessageId, mergeSteerReceipt(entries.get(receipt.clientMessageId), receipt))
  }
  return [...entries.values()]
}

export function applySteerReceipt(history: Run[], receipt: SteerReceipt): Run[] {
  return history.map(turn => turn.id !== receipt.runId ? turn : { ...turn, steers: mergeReceipts(turn.steers, [receipt]) })
}

export function mergeRunSnapshot(current: Run[], incoming: Run[]): Run[] {
  const existing = new Map(current.map(run => [run.id, run]))
  const merged = incoming.map(run => {
    const local = existing.get(run.id)
    existing.delete(run.id)
    if (!local) return run
    // 活动快照不覆盖流式尾巴；终态快照是消息正文的最终来源。
    if (run.status === RUNNING && local.status !== RUNNING) return local
    const messages = run.status === RUNNING ? [...run.messages, ...local.messages.slice(run.messages.length)] : run.messages
    const reason = run.status !== RUNNING ? TERMINAL
      : (local.steerCloseReason ?? run.steerCloseReason)
    return { ...run, eventSeq: local.eventSeq, messages, steerCloseReason: reason,
      canSteer: !reason && run.canSteer === true, steers: mergeReceipts(local.steers, run.steers) }
  })
  return [...merged, ...existing.values()]
}

export function applyRunEvent(history: Run[], event: RunEvent, question: string): Run[] {
  if (event.type === STARTED && !history.some(turn => turn.id === event.runId)) {
    return [...history, { id: event.runId, status: RUNNING, canSteer: event.canSteer === true,
      eventSeq: event.seq, messages: [{ role: USER, content: question, calls: [] }], steers: [] }]
  }
  return history.map(turn => {
    // 重复、迟到的帧不能让状态倒退或再次拼接文字。
    if (turn.id !== event.runId || turn.status !== RUNNING || (turn.eventSeq ?? 0) >= event.seq) return turn
    const tracked = { ...turn, eventSeq: event.seq }
    if (event.type === STEER_GATE) {
      return { ...tracked, canSteer: false, steerCloseReason: event.steerCloseReason ?? LIMIT }
    }
    if (event.type === FAILURE) {
      return { ...tracked, status: FAILED, failure: event.text ?? undefined, canSteer: false, steerCloseReason: TERMINAL }
    }
    const messages = [...turn.messages]
    const index = event.messageSeq
    if (event.type === STEER_INCORPORATED && event.clientMessageId) {
      const text = event.text ?? ''
      messages[index] = { role: USER, content: text, calls: [] }
      return { ...tracked, messages, steers: mergeReceipts(turn.steers, [{ runId: turn.id,
        clientMessageId: event.clientMessageId, message: text, runStatus: RUNNING, messageSeq: index }]) }
    }
    const assistant: Message = messages[index] ?? { role: ASSISTANT, content: '', calls: [] }
    if (event.type === DELTA || event.type === REPLY_COMMITTED || event.type === COMPLETED) {
      messages[index] = { ...assistant, content: (event.type === DELTA ? assistant.content : '') + (event.text ?? '') }
    }
    if ((event.type === TOOL_STARTED || event.type === TOOL_FINISHED) && event.call) {
      const call = event.call
      if (!assistant.calls.some(old => old.id === call.id)) {
        messages[index] = { ...assistant, calls: [...assistant.calls, call] }
      }
      if (event.result) {
        // 工具结果的位置由当前助手消息及已写入的连续结果决定。
        let next = index + 1
        while (messages[next]?.role === TOOL) next++
        messages[next] = event.result
      }
    }
    return event.type === COMPLETED
      ? { ...tracked, messages, status: SUCCEEDED, canSteer: false, steerCloseReason: TERMINAL }
      : { ...tracked, messages }
  })
}
