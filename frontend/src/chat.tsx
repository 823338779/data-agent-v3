import { memo, useEffect, useRef, useState } from 'react'
import Markdown from 'react-markdown'
import { createSession, recentSessions, runAndRestore, restoreSession } from './chat-api'
import type { Message, RecentSession, Run, RunEvent, Snapshot } from './chat-api'

const SESSION_PARAM = 'session'
const STARTED = 'run.started', COMPLETED = 'run.completed', FAILED = 'run.failed'
const DELTA = 'text.delta', TOOL_STARTED = 'tool.started', TOOL_FINISHED = 'tool.finished'
const RUNNING = 'RUNNING', SUCCEEDED = 'SUCCEEDED', FAILURE = 'FAILED'
const USER = 'user', ASSISTANT = 'assistant', TOOL = 'tool'
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
  })}{turn.failure && <p className="error" role="alert">{turn.failure}</p>}</article>
})

export function Chat() {
  const [session, setSession] = useState('')
  const [history, setHistory] = useState<Run[]>([])
  const [busy, setBusy] = useState(true)
  const [question, setQuestion] = useState('')
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
        if (alive) {
          setSession('')
          setError(cause instanceof Error ? cause.message : '会话加载失败')
        }
      } finally {
        if (alive) setBusy(false)
      }
    }
    void load()
    return () => { alive = false; controller.abort() }
  }, [])

  useEffect(() => {
    // 逐块跟随流式回复，用户向上翻阅时保留当前位置。
    const panel = conversation.current
    if (panel && follow.current) panel.scrollTop = panel.scrollHeight
  }, [history, busy])

  async function send() {
    const text = question.trim()
    if (!text || !session || busy || sending.current) return
    sending.current = true
    follow.current = true
    setBusy(true)
    setError('')
    let accepted = false
    try {
      await runAndRestore(session, text, event => {
        if (event.type === STARTED) {
          accepted = true
          setQuestion('')
          setRevision(value => value + 1)
        }
        setHistory(previous => applyEvent(previous, event, text))
      }, receive, setError)
    } catch (cause) {
      if (!accepted) setError(cause instanceof Error ? cause.message : '连接中断，请核对会话记录。')
    } finally {
      sending.current = false
      setBusy(false)
      setRevision(value => value + 1)
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
        <span aria-hidden="true" className="panel-icon" /></button><span>Data Agent V3</span></header>
      <section className="conversation" aria-label="对话记录" ref={conversation} onScroll={event => {
        const panel = event.currentTarget
        follow.current = panel.scrollHeight - panel.scrollTop - panel.clientHeight < 64
      }}>
        <div className="thread">
          <div className="welcome">欢迎使用 Data Agent V3</div>
          {!history.length && !busy && !error && <div className="examples">{EXAMPLES.map(example =>
            <button key={example} onClick={() => { setQuestion(example); input.current?.focus() }}>{example}</button>)}</div>}
          {history.map(turn => <Transcript key={turn.id} turn={turn} />)}
          {busy && <p className="status" role="status">{session ? '正在处理…' : '正在打开会话…'}</p>}
          {error && <p className="error" role="alert">{error}</p>}
        </div>
      </section>
    <form className="composer" onSubmit={event => { event.preventDefault(); void send() }}>
      <textarea id="question" ref={input} aria-label="消息" rows={2} value={question} placeholder="输入问题或继续追问…"
        onChange={event => setQuestion(event.target.value)} onKeyDown={event => {
          if (event.key === ENTER && !event.shiftKey && !event.nativeEvent.isComposing) {
            event.preventDefault(); void send()
          }
        }} />
      <div className="composer-tools"><span>Shift + Enter 换行</span>
        <button className="send" aria-label="发送消息" title="发送消息" disabled={!session || busy || !question.trim()}>
          <span aria-hidden="true">↑</span></button></div>
    </form>
    </main>
  </div>
}
