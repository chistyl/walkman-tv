const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('node:vm')

const preload = fs.readFileSync(
  path.resolve(__dirname, '../../main/assets/script/user-api-preload.js'),
  'utf8',
)

function loadPreloadWithoutNativeUrlSearchParams() {
  const nativeCalls = []
  const context = vm.createContext({
    URLSearchParams: undefined,
    __lx_native_call__: (_key, action, data) => nativeCalls.push({ action, data: JSON.parse(data) }),
    __lx_native_call__set_timeout: () => {},
    __lx_native_call__utils_str2b64: () => '',
    __lx_native_call__utils_b642buf: () => '[]',
    __lx_native_call__utils_str2md5: () => '',
    __lx_native_call__utils_aes_encrypt: () => '',
    __lx_native_call__utils_rsa_encrypt: () => '',
    console: { log() {}, info() {}, warn() {}, error() {} },
  })
  vm.runInContext(preload, context)
  vm.runInContext("lx_setup('key', 'id', 'name', '', '1', '', '', '')", context)
  return { context, nativeCalls }
}

test('QuickJS preload supplies a compatible URLSearchParams implementation', () => {
  const { context } = loadPreloadWithoutNativeUrlSearchParams()

  const result = vm.runInContext(`(() => {
    const params = new URLSearchParams('source=kw&tag=first')
    params.append('tag', 'second value')
    params.set('rid', 'MUSIC_123')
    const seen = []
    params.forEach(function(value, name, owner) {
      seen.push([name, value, owner === params, this.marker])
    }, { marker: 'bound' })
    const entries = Array.from(params.entries())
    const iterated = Array.from(params)
    const mutablePair = params.entries().next().value
    mutablePair[1] = 'must not leak into params'
    params.delete('tag')
    return JSON.stringify({
      source: params.get('source'),
      tagsBeforeDelete: entries.filter(([name]) => name === 'tag').map(([, value]) => value),
      hasTagAfterDelete: params.has('tag'),
      hasRid: params.has('rid'),
      allSources: params.getAll('source'),
      sourceAfterIteratorMutation: params.get('source'),
      missing: params.get('missing'),
      entries,
      iterated,
      seen,
      entriesIsMethod: typeof params.entries === 'function',
      ownKeys: Object.keys(params),
      query: params.toString(),
    })
  })()`, context)

  assert.deepEqual(JSON.parse(result), {
    source: 'kw',
    tagsBeforeDelete: ['first', 'second value'],
    hasTagAfterDelete: false,
    hasRid: true,
    allSources: ['kw'],
    sourceAfterIteratorMutation: 'kw',
    missing: null,
    entries: [
      ['source', 'kw'],
      ['tag', 'first'],
      ['tag', 'second value'],
      ['rid', 'MUSIC_123'],
    ],
    iterated: [
      ['source', 'kw'],
      ['tag', 'first'],
      ['tag', 'second value'],
      ['rid', 'MUSIC_123'],
    ],
    seen: [
      ['source', 'kw', true, 'bound'],
      ['tag', 'first', true, 'bound'],
      ['tag', 'second value', true, 'bound'],
      ['rid', 'MUSIC_123', true, 'bound'],
    ],
    entriesIsMethod: true,
    ownKeys: [],
    query: 'source=kw&rid=MUSIC_123',
  })
})


test('preload preserves supported search and songlist actions and long custom quality keys', async () => {
  const { context, nativeCalls } = loadPreloadWithoutNativeUrlSearchParams()
  await vm.runInContext(`lx.send('inited', { sources: {
    custom: { type: 'music', actions: ['search', 'songlist', 'musicUrl', 'unknown'], qualitys: ['320k', 'lossless_custom_tier_1234567890123456789012345678901234567890'] },
    local: { type: 'music', actions: ['musicUrl'], qualitys: ['320k'] }
  } })`, context)

  const init = nativeCalls.find(call => call.action === 'init')
  assert.ok(init)
  assert.deepEqual(init.data.info.sources.custom.actions, ['search', 'songlist', 'musicUrl'])
  assert.deepEqual(init.data.info.sources.custom.qualitys, [
    '320k',
    'lossless_custom_tier_1234567890123456789012345678901234567890',
  ])
  assert.deepEqual(init.data.info.sources.local.qualitys, [])
})

test('preload returns structured search and songlist results without URL wrapping', async () => {
  const { context, nativeCalls } = loadPreloadWithoutNativeUrlSearchParams()
  vm.runInContext(`lx.on('request', async ({ action }) => ({
    action,
    data: [{ id: 'song-1', name: 'Song' }]
  }))`, context)

  for (const action of ['search', 'songlist']) {
    vm.runInContext(`__lx_native__('key', 'request', JSON.stringify({
      requestKey: 'req-${action}',
      data: { source: 'custom', action, info: {} }
    }))`, context)
    await new Promise(resolve => setImmediate(resolve))
    const response = nativeCalls.find(call =>
      call.action === 'response' && call.data.requestKey === `req-${action}`,
    )
    assert.ok(response)
    assert.equal(response.data.status, true)
    assert.deepEqual(response.data.result, {
      action,
      data: [{ id: 'song-1', name: 'Song' }],
    })
  }
})
