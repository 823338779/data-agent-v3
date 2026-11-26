import { memo, useEffect, useRef, useState } from 'react'
import Markdown from 'react-markdown'
import { createSession, recentSessions, runAndRestore, restoreSession, SteerRejection, submitSteer } from './chat-api'
import type { Message, RecentSession, Run, Snapshot, SteerInput, SteerReceipt } from './chat-api'
import { applyRunEvent, applySteerReceipt, deriveComposerMode, deriveSteerDisplay, mergeRunSnapshot } from './steer-state'

const SESSION_PARAM = 'session'
const STARTED = 'run.started', INCORPORATED = 'run.steer_incorporated'
const RUNNING = 'RUNNING'
const USER = 'user', TOOL = 'tool'
const STEER_STORAGE = 'data-agent-v3.steer:'
const STEER_CONFIRMING = '正在确认补充'
const SEND = '发送', STEER = '补充本轮'
type PendingSteer = SteerInput & { runId: string }
const labels: Record<string, string> = { current_time: '查询当前时间', convert_timezone: '换算时区' }
const MOBILE = '(max-width: 44rem)'
const DARK = 'dark', LIGHT = 'light', THEME_KEY = 'data-agent-v3.theme'
const NEW_CHAT = '新建对话', ENTER = 'Enter'
const EXAMPLES = ['UTC 现在几点？', '北京时间上午 9 点是东京几点？']
const NO_EMBEDS = ['img']
const dateFormat = new Intl.DateTimeFormat('zh-CN', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })

function chatUrl(id?: string) {
  // 新建与切换均使用真实链接，浏览器前进、后退和分享沿用相同入口。
  const url = new URL(location.href)
  if (id) url.searchParams.set(SESSION_PARAM, id)
  else url.searchParams.delete(SESSION_PARAM)
  return url.toString()
}

function loadPending(sessionId: string): PendingSteer | null {
  try {
    const raw = sessionStorage.getItem(STEER_STORAGE + sessionId)
    if (!raw) return null
    const pending = JSON.parse(raw)
    return typeof pending?.runId === 'string' && typeof pending.clientMessageId === 'string'
      && typeof pending.message === 'string' ? pending : null
  } catch { return null }
}

function savePending(sessionId: string, pending: PendingSteer | null) {
  try {
    if (pending) sessionStorage.setItem(STEER_STORAGE + sessionId, JSON.stringify(pending))
    else sessionStorage.removeItem(STEER_STORAGE + sessionId)
  } catch { /* 存储受限时，本页内仍保留重试标识。 */ }
}

// 已结束的轮次保持引用稳定，新文本到达时仅解析当前轮的 Markdown。
const Transcript = memo(function Transcript({ turn }: { turn: Run }) {
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
      {message.content && <div className="reply"><Markdown skipHtml disallowedElements={NO_EMBEDS}>
        {message.content}
      </Markdown></div>}
      {message.calls.map(call => {
        const result = results.get(call.id)
        const status = result ? (result.success ? '完成' : '失败') : (turn.status === RUNNING ? '执行中' : '中断')
        return <details className="tool" key={call.id}><summary>
          <span>{labels[call.name] ?? call.name}</span><small>{status}</small></summary>
          <dl><dt>调用参数</dt><dd><pre>{call.arguments}</pre></dd>
            {result && <><dt>执行结果</dt><dd><pre>{result.content}</pre></dd></>}</dl>
        </details>
      })}
    </div>
  })}{turn.steers?.filter(receipt => deriveSteerDisplay(receipt, turn.status) !== 'message').map(receipt => {
    const waiting = deriveSteerDisplay(receipt, turn.status) === 'waiting'
    return <div className="steer-receipt" key={receipt.clientMessageId}>
      <span>{receipt.message}</span><small>{waiting ? '等待纳入' : '未使用'}</small>
    </div>
  })}{turn.failure && <p className="error" role="alert">{turn.failure}</p>}</article>
})

export function Chat() {
  const [session, setSession] = useState('')
  const [history, setHistory] = useState<Run[]>([])
  const [busy, setBusy] = useState(true)
  const [question, setQuestion] = useState('')
  const [pending, setPending] = useState<PendingSteer | null>(null)
  const [steerSending, setSteerSending] = useState(false)
  const [steerError, setSteerError] = useState('')
  const [rejectedDraft, setRejectedDraft] = useState('')
  const [error, setError] = useState('')
  const [recent, setRecent] = useState<RecentSession[]>([])
  const [listError, setListError] = useState('')
  const [revision, setRevision] = useState(0)
  const [sidebar, setSidebar] = useState(() => !window.matchMedia(MOBILE).matches)
  const [theme, setTheme] = useState(() => {
    try { return localStorage.getItem(THEME_KEY) === DARK ? DARK : LIGHT } catch { return LIGHT }
  })
  const input = useRef<HTMLTextAreaElement>(null)
  const conversation = useRef<HTMLElement>(null)
  const follow = useRef(true)
  const sending = useRef(false)
  const requestInFlight = useRef(false)
  const pendingRef = useRef<PendingSteer | null>(null)
  const pendingDraftRevision = useRef(-1)
  const draftRevision = useRef(0)
  const sessionRef = useRef('')
  const lifecycle = useRef<AbortController | null>(null)
  const active = history.find(run => run.status === RUNNING)
  const mode = deriveComposerMode(active?.id ?? null, active, busy, Boolean(pending))

  function editDraft(text: string) {
    draftRevision.current++
    setQuestion(text)
  }

  function updatePending(id: string, value: PendingSteer | null) {
    if (id !== sessionRef.current) return
    pendingRef.current = value
    setPending(value)
    savePending(id, value)
  }

  function confirmSteer(id: string, receipt: SteerReceipt) {
    if (id !== sessionRef.current) return
    setHistory(previous => applySteerReceipt(previous, receipt))
    const waiting = pendingRef.current
    if (waiting?.runId !== receipt.runId || waiting.clientMessageId !== receipt.clientMessageId) return
    updatePending(id, null)
    setSteerError('')
    if (draftRevision.current === pendingDraftRevision.current) setQuestion('')
  }

  async function transmitSteer(id: string, sendingSteer: PendingSteer) {
    if (id !== sessionRef.current || requestInFlight.current) return
    requestInFlight.current = true
    setSteerSending(true)
    setSteerError('')
    const signal = lifecycle.current?.signal
    try {
      const { runId, clientMessageId, message } = sendingSteer
      confirmSteer(id, await submitSteer(id, runId, { clientMessageId, message }, signal))
    } catch (cause) {
      if (signal?.aborted || id !== sessionRef.current || pendingRef.current !== sendingSteer) return
      if (cause instanceof SteerRejection) {
        updatePending(id, null)
        setSteerError(cause.message)
        if (cause.code === 'STEER_CLOSED') {
          setHistory(previous => previous.map(turn => turn.id === sendingSteer.runId
            ? { ...turn, canSteer: false, steerCloseReason: cause.reason } : turn))
        }
        // 不覆盖等待回执时新增的草稿，拒绝的旧文本仍可手动找回。
        setQuestion(current => current || sendingSteer.message)
        setRejectedDraft(sendingSteer.message)
      } else {
        setSteerError('连接中断，本次补充尚未确认。')
      }
    } finally {
      requestInFlight.current = false
      if (!signal?.aborted && id === sessionRef.current) setSteerSending(false)
    }
  }

  useEffect(() => {
    // 主题仅影响本机界面，禁用浏览器存储时仍然允许临时切换。
    document.documentElement.dataset.theme = theme
    try { localStorage.setItem(THEME_KEY, theme) } catch { /* 存储不可用时保留当前页面的选择。 */ }
  }, [theme])

  useEffect(() => {
    // 独立刷新目录，列表加载失败时当前对话仍可继续。
    const controller = new AbortController()
    setListError('')
    recentSessions(controller.signal).then(rows => {
      if (!controller.signal.aborted) setRecent(rows)
    }).catch(() => {
      if (!controller.signal.aborted) setListError('最近会话加载失败')
    })
    return () => controller.abort()
  }, [revision])

  function receive(state: Snapshot) {
    if (state.sessionId !== sessionRef.current) return
    setHistory(previous => mergeRunSnapshot(previous, state.runs))
    const waiting = pendingRef.current
    if (waiting) {
      const receipt = state.runs.find(run => run.id === waiting.runId)?.steers
        ?.find(row => row.clientMessageId === waiting.clientMessageId)
      if (receipt) confirmSteer(state.sessionId, receipt)
    }
    setError('')
  }

  useEffect(() => {
    let alive = true
    const controller = new AbortController()
    lifecycle.current = controller
    async function load() {
      try {
        const url = new URL(location.href)
        const id = url.searchParams.get(SESSION_PARAM) || await createSession()
        if (!alive) return
        url.searchParams.set(SESSION_PARAM, id)
        window.history.replaceState(null, '', url)
        sessionRef.current = id
        setSession(id)
        const unconfirmed = loadPending(id)
        if (unconfirmed) updatePending(id, unconfirmed)
        await restoreSession(id, receive, setError, controller.signal)
      } catch (cause) {
        if (alive) {
          setSession('')
          setError(cause instanceof Error ? cause.message : '会话加载失败')
        }
      } finally {
        if (alive) setBusy(false)
      }
    }
    void load()
    return () => { alive = false; sessionRef.current = ''; controller.abort() }
  }, [])

  useEffect(() => {
    // 逐块跟随流式回复，用户向上翻阅时保留当前位置。
    const panel = conversation.current
    if (panel && follow.current) panel.scrollTop = panel.scrollHeight
  }, [history, busy])

  async function send() {
    if (!question.trim() || !session || mode === 'disabled') return
    const id = session
    if (mode === 'steer' && active) {
      // 每次点击冻结目标 Run 与原文，重试复用该 UUID，不会改成下一轮。
      if (pendingRef.current || requestInFlight.current) return
      const steer = { runId: active.id, clientMessageId: crypto.randomUUID(), message: question }
      pendingDraftRevision.current = draftRevision.current
      updatePending(id, steer)
      setRejectedDraft('')
      await transmitSteer(id, steer)
      return
    }
    if (sending.current) return
    const text = question.trim()
    const revisionAtSend = draftRevision.current
    const signal = lifecycle.current?.signal
    sending.current = true
    follow.current = true
    setBusy(true)
    setError('')
    let accepted = false
    try {
      await runAndRestore(id, text, event => {
        if (signal?.aborted || id !== sessionRef.current) return
        if (event.type === STARTED) {
          accepted = true
          if (draftRevision.current === revisionAtSend) setQuestion('')
          setRevision(value => value + 1)
        }
        setHistory(previous => applyRunEvent(previous, event, text))
        if (event.type === INCORPORATED && event.clientMessageId) {
          confirmSteer(id, { runId: event.runId, clientMessageId: event.clientMessageId,
            message: event.text ?? '', messageSeq: event.messageSeq, runStatus: RUNNING })
        }
      }, receive, setError, signal)
    } catch (cause) {
      if (!accepted && !signal?.aborted) setError(cause instanceof Error ? cause.message : '连接中断，请核对会话记录。')
    } finally {
      sending.current = false
      if (!signal?.aborted && id === sessionRef.current) {
        setBusy(false)
        setRevision(value => value + 1)
      }
    }
  }

  // 桌面显示会话侧栏，窄屏使用同一个可收起的导航区。
  return <div className="workspace">
    <aside id="sessions" hidden={!sidebar} aria-label="会话侧栏">
      <a className="new-chat" href={chatUrl()}><span aria-hidden="true">＋</span>{NEW_CHAT}</a>
      <nav aria-label="最近会话">
        <h2>最近会话</h2>
        {recent.map(row => <a key={row.id} href={chatUrl(row.id)} title={row.title}
          aria-current={row.id === session ? 'page' : undefined}>
          <strong>{row.title}</strong><time dateTime={row.updatedAt}>{dateFormat.format(new Date(row.updatedAt))}</time>
        </a>)}
        {listError && <div className="list-error" role="alert">{listError} <button onClick={() => setRevision(value => value + 1)}>重试</button></div>}
      </nav>
      <footer><button onClick={() => setTheme(theme === DARK ? LIGHT : DARK)}>{theme === DARK ? '☀ 浅色' : '☾ 深色'}</button>
        <a href="/actuator/health">后端状态</a></footer>
    </aside>
    <main>
      <header><button className="panel-toggle" aria-controls="sessions" aria-expanded={sidebar}
        aria-label={sidebar ? '收起会话侧栏' : '展开会话侧栏'} onClick={() => setSidebar(!sidebar)}>
        <span aria-hidden="true" className="panel-icon" /></button><span>Data Agent</span></header>
      <section className="conversation" aria-label="对话记录" ref={conversation} onScroll={event => {
        const panel = event.currentTarget
        follow.current = panel.scrollHeight - panel.scrollTop - panel.clientHeight < 64
      }}>
        <div className="thread">
          <div className="welcome">欢迎使用 Data Agent</div>
          {!history.length && !busy && !error && <div className="examples">{EXAMPLES.map(example =>
            <button key={example} onClick={() => { editDraft(example); input.current?.focus() }}>{example}</button>)}</div>}
          {history.map(turn => <Transcript key={turn.id} turn={turn} />)}
          {busy && <p className="status" role="status">{session ? '正在处理…' : '正在打开会话…'}</p>}
          {error && <p className="error" role="alert">{error}</p>}
        </div>
      </section>
    <form className="composer" onSubmit={event => { event.preventDefault(); void send() }}>
      <textarea id="question" ref={input} aria-label="消息" rows={2} value={question} placeholder="输入问题或继续追问…"
        onChange={event => editDraft(event.target.value)} onKeyDown={event => {
          if (event.key === ENTER && !event.shiftKey && !event.nativeEvent.isComposing) {
            event.preventDefault(); void send()
          }
        }} />
      {pending && <div className="steer-status" role="status">{STEER_CONFIRMING}
        {!steerSending && <button type="button" onClick={() => void transmitSteer(session, pending)}>重试确认</button>}
      </div>}
      {steerError && <div className="steer-status error" role="alert">{steerError}
        {rejectedDraft && question !== rejectedDraft && <button type="button" onClick={() => {
          editDraft(question ? question + '\n' + rejectedDraft : rejectedDraft)
          setRejectedDraft('')
        }}>找回补充草稿</button>}
      </div>}
      <div className="composer-tools"><span>{active?.steerCloseReason === 'LIMIT'
        ? '本轮已开始最后一次模型请求' : 'Shift + Enter 换行'}</span>
        <button className={mode === 'steer' ? 'send steer-send' : 'send'} aria-label={active ? STEER : SEND}
          title={active ? STEER : SEND} disabled={!session || mode === 'disabled' || !question.trim()}>
          {active ? STEER : <span aria-hidden="true">↑</span>}</button></div>
    </form>
    </main>
  </div>
}
