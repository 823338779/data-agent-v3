import { useEffect, useRef, useState } from 'react'
import { createSession, runAndRestore, restoreSession } from './chat-api'
import type { Message, Run, RunEvent, Snapshot } from './chat-api'

const SESSION_PARAM = 'session'
const STARTED = 'run.started', COMPLETED = 'run.completed', FAILED = 'run.failed'
const DELTA = 'text.delta', TOOL_STARTED = 'tool.started', TOOL_FINISHED = 'tool.finished'
const RUNNING = 'RUNNING', SUCCEEDED = 'SUCCEEDED', FAILURE = 'FAILED'
const USER = 'user', ASSISTANT = 'assistant', TOOL = 'tool'
const labels: Record<string, string> = { current_time: '查询当前时间', convert_timezone: '换算时区' }

function applyEvent(history: Run[], event: RunEvent, question: string): Run[] {
  // SSE 与快照共用消息结构，最终消息覆盖同位置的流式文本。
  if (event.type === STARTED) return [...history, { id: event.runId, status: RUNNING,
    messages: [{ role: USER, content: question, calls: [] }] }]
  return history.map(turn => {
    if (turn.id !== event.runId) return turn
    if (event.type === FAILED) return { ...turn, status: FAILURE, failure: event.text ?? undefined }
    const messages = [...turn.messages]
    const index = event.messageSeq
    const assistant = messages[index] ?? { role: ASSISTANT, content: '', calls: [] }
    if (event.type === DELTA || event.type === COMPLETED) {
      messages[index] = { ...assistant, content: (event.type === DELTA ? assistant.content : '') + (event.text ?? '') }
    }
    if ((event.type === TOOL_STARTED || event.type === TOOL_FINISHED) && event.call) {
      const call = event.call
      if (!assistant.calls.some(old => old.id === call.id)) {
        messages[index] = { ...assistant, calls: [...assistant.calls, call] }
      }
      if (event.result) messages.push(event.result)
    }
    return { ...turn, messages, status: event.type === COMPLETED ? SUCCEEDED : turn.status }
  })
}

function Transcript({ turn }: { turn: Run }) {
  return <article className="turn">{turn.messages.map((message, index) => {
    if (message.role === TOOL) return null
    if (message.role === USER) return <div className="question" key={index}>{message.content}</div>
    // 同一助手消息后的工具结果按调用 ID 关联，避免跨模型请求误配。
    const results = new Map<string | undefined, Message>()
    for (let pos = index + 1; pos < turn.messages.length && turn.messages[pos].role === TOOL; pos++) {
      const result = turn.messages[pos]
      results.set(result.toolCallId, result)
    }
    return <div className="answer" key={index}>
      {message.content && <p>{message.content}</p>}
      {message.calls.map(call => {
        const result = results.get(call.id)
        const status = result ? (result.success ? '完成' : '失败') : (turn.status === RUNNING ? '执行中' : '中断')
        return <details className="tool" key={call.id}><summary>
          {labels[call.name] ?? call.name}<small>{status}</small></summary>
          <pre>{call.arguments}</pre>{result && <pre>{result.content}</pre>}
        </details>
      })}
    </div>
  })}{turn.failure && <p className="error" role="alert">{turn.failure}</p>}</article>
}

export function Chat() {
  const [session, setSession] = useState('')
  const [history, setHistory] = useState<Run[]>([])
  const [busy, setBusy] = useState(true)
  const [question, setQuestion] = useState('')
  const [error, setError] = useState('')
  const sending = useRef(false)
  const bottom = useRef<HTMLDivElement>(null)

  function receive(state: Snapshot) {
    setHistory(state.runs)
    setError('')
  }

  useEffect(() => {
    let alive = true
    const controller = new AbortController()
    async function load() {
      try {
        const url = new URL(location.href)
        const id = url.searchParams.get(SESSION_PARAM) || await createSession()
        if (!alive) return
        url.searchParams.set(SESSION_PARAM, id)
        window.history.replaceState(null, '', url)
        setSession(id)
        await restoreSession(id, receive, setError, controller.signal)
      } catch (cause) {
        if (alive) setError(cause instanceof Error ? cause.message : '会话加载失败')
      } finally {
        if (alive) setBusy(false)
      }
    }
    void load()
    return () => { alive = false; controller.abort() }
  }, [])

  useEffect(() => { bottom.current?.scrollIntoView({ block: 'nearest' }) }, [history.length, busy])

  async function send() {
    const text = question.trim()
    if (!text || !session || busy || sending.current) return
    sending.current = true
    setBusy(true)
    setError('')
    let accepted = false
    try {
      await runAndRestore(session, text, event => {
        if (event.type === STARTED) { accepted = true; setQuestion('') }
        setHistory(previous => applyEvent(previous, event, text))
      }, receive, setError)
    } catch (cause) {
      if (!accepted) setError(cause instanceof Error ? cause.message : '连接中断，请核对会话记录。')
    } finally {
      sending.current = false
      setBusy(false)
    }
  }

  return <main className="workspace">
    <header><span className="brand">Data Agent</span><a href="/actuator/health">后端状态</a></header>
    <section className="conversation" aria-label="对话记录">
      {!history.length && <div className="intro"><h1>有什么想问的？</h1><p>查询当前时间、换算时区，或继续追问。</p></div>}
      {history.map(turn => <Transcript key={turn.id} turn={turn} />)}
      {busy && <p className="status" role="status">{session ? '正在处理…' : '正在打开会话…'}</p>}
      {error && <p className="error" role="alert">{error}</p>}
      <div ref={bottom} />
    </section>
    <form onSubmit={event => { event.preventDefault(); void send() }}>
      <label htmlFor="question">消息</label>
      <textarea id="question" rows={2} value={question} placeholder="输入问题…"
        onChange={event => setQuestion(event.target.value)} onKeyDown={event => {
          if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
            event.preventDefault(); void send()
          }
        }} />
      <button disabled={!session || busy || !question.trim()}>发送</button>
    </form>
  </main>
}
