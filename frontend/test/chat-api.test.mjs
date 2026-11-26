import test from 'node:test'
import assert from 'node:assert/strict'
import { readEvents } from '../src/chat-api.ts'
import * as chat from '../src/chat-api.ts'

test('在中文 UTF-8 字符和 CRLF 帧边界中间切分仍可解码', async () => {
  const raw = 'event:text.delta\r\ndata:{"seq":1,"text":"时间"}\r\n\r\nevent:run.completed\ndata:{"seq":2,"text":"完成"}\n\n'
  const bytes = new TextEncoder().encode(raw)
  const stream = new ReadableStream({start(controller) {
    for (const byte of bytes) controller.enqueue(Uint8Array.of(byte))
    controller.close()
  }})
  const received = []
  await readEvents(stream, event => received.push(event))
  assert.deepEqual(received.map(({type, seq, text}) => [type, seq, text]), [
    ['text.delta', 1, '时间'], ['run.completed', 2, '完成'],
  ])
})

test('提交连接中断且首次快照失败时持续恢复，只发送一次 POST', async context => {
  const methods = []
  const statuses = []
  let reads = 0
  context.mock.method(globalThis, 'fetch', async (_url, options) => {
    methods.push(options?.method ?? 'GET')
    if (options?.method === 'POST') {
      const stream = new ReadableStream({ pull(controller) { controller.error(new TypeError('offline')) } })
      return new Response(stream)
    }
    if (++reads === 1) throw new TypeError('offline')
    const active = reads === 2
    return Response.json({ sessionId: 's', activeRunId: active ? 'r' : null,
      runs: [{id: 'r', status: active ? 'RUNNING' : 'SUCCEEDED', messages: []}] })
  })
  await assert.rejects(chat.runAndRestore('s', 'hello', () => {}, state => statuses.push(state.runs[0].status), () => {}), /offline/)
  assert.deepEqual(methods, ['POST', 'GET', 'GET', 'GET'])
  assert.deepEqual(statuses, ['RUNNING', 'SUCCEEDED'])
})

test('补充网络失败后重试仍发送原 Run 和 UUID', async context => {
  const input = {clientMessageId: 'same-id', message: '也给我北京时间'}
  const sent = []
  context.mock.method(globalThis, 'fetch', async (url, options) => {
    sent.push([url, options.method, JSON.parse(options.body)])
    if (sent.length === 1) throw new TypeError('offline')
    return Response.json({runId: 'old-run', ...input, runStatus: 'SUCCEEDED', messageSeq: 2})
  })
  await assert.rejects(chat.submitSteer('s', 'old-run', input), /offline/)
  assert.equal((await chat.submitSteer('s', 'old-run', input)).messageSeq, 2)
  assert.deepEqual(sent, Array(2).fill(['/api/sessions/s/runs/old-run/steers', 'POST', input]))
})
