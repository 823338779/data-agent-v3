import test from 'node:test'
import assert from 'node:assert/strict'
import * as state from '../src/steer-state.ts'

const receipt = {runId: 'r', clientMessageId: 'u', message: '补充', runStatus: 'RUNNING', messageSeq: null}
const run = {id: 'r', status: 'RUNNING', canSteer: true, messages: [], steers: []}
const event = (type, seq, fields = {}) => ({type, seq, runId: 'r', messageSeq: 1, text: null,
  call: null, result: null, ...fields})

test('回答已开始仍可补充，最后一次请求或终态不能被旧事件重新打开', () => {
  const streaming = state.applyRunEvent([run], event('text.delta', 1, {text: '先回答'}), '')[0]
  assert.equal(state.deriveComposerMode('r', streaming, true, false), 'steer')
  const limited = state.applyRunEvent([streaming], event('run.steer_gate', 4, {
    canSteer: false, steerCloseReason: 'LIMIT',
  }), '')[0]
  assert.equal(state.deriveComposerMode('r', limited, true, false), 'disabled')
  const stale = state.applyRunEvent([limited], event('run.steer_gate', 2, {canSteer: true}), '')[0]
  assert.equal(stale.canSteer, false)
  const terminal = {...limited, status: 'SUCCEEDED', steerCloseReason: 'TERMINAL'}
  assert.equal(state.applyRunEvent([terminal], event('run.steer_gate', 5, {canSteer: true}), '')[0], terminal)
  assert.equal(state.deriveComposerMode('r', streaming, true, true), 'disabled')
})

test('晚到的回执不清除消息序号或终态，续答前只插入一个用户气泡', () => {
  const incorporated = {...receipt, runStatus: 'SUCCEEDED', messageSeq: 2}
  assert.deepEqual(state.mergeSteerReceipt(incorporated, receipt), incorporated)
  let history = state.applyRunEvent([], event('run.started', 1, {canSteer: true}), '问题')
  history = state.applyRunEvent(history, event('run.reply_committed', 2, {text: '先回答'}), '')
  const inserted = event('run.steer_incorporated', 3, {messageSeq: 2, text: '补充', clientMessageId: 'u'})
  history = state.applyRunEvent(history, inserted, '')
  history = state.applyRunEvent(history, inserted, '')
  assert.deepEqual(history[0].messages.map(m => m.content), ['问题', '先回答', '补充'])
  assert.equal(state.deriveSteerDisplay(receipt, 'FAILED'), 'unused')
  assert.equal(state.deriveSteerDisplay(incorporated, 'FAILED'), 'message')
})

test('活动快照保留未落库尾巴，终态以服务端记录为准', () => {
  const question = {role: 'user', content: '问题', calls: []}
  const answer = {role: 'assistant', content: '先回答', calls: []}
  const local = {...run, messages: [question, answer], canSteer: false, steerCloseReason: 'LIMIT'}
  assert.deepEqual(state.mergeRunSnapshot([local], [{...run, messages: [question]}])[0].messages, [question, answer])
  assert.equal(state.mergeRunSnapshot([local], [{...run, messages: [question]}])[0].canSteer, false)
  const finished = {...run, status: 'FAILED', messages: [question], canSteer: false}
  assert.deepEqual(state.mergeRunSnapshot([local], [finished])[0].messages, [question])
})
