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


async function invokeMusicUrlResponse(response) {
  const { context, nativeCalls } = loadPreloadWithoutNativeUrlSearchParams()
  const responseLiteral = JSON.stringify(response)
  vm.runInContext(`lx.on('request', async () => (${responseLiteral}))`, context)
  vm.runInContext(
    `__lx_native__('key', 'request', JSON.stringify({
      requestKey: 'music-url-test',
      data: { source: 'kg', action: 'musicUrl', info: { type: '320k' } },
    }))`,
    context,
  )
  await new Promise(resolve => setImmediate(resolve))
  const nativeResponse = nativeCalls.find(call => call.action === 'response')
  assert.ok(nativeResponse, 'musicUrl response was not returned to native')
  return nativeResponse.data
}

test('musicUrl preserves per-track headers and userAgent from object responses', async () => {
  const payload = await invokeMusicUrlResponse({
    url: 'https://cdn.example.com/song.mp3',
    headers: { Referer: 'https://www.example.com/' },
    userAgent: 'ExamplePlayer/1.0',
  })

  assert.equal(payload.status, true)
  assert.deepEqual(payload.result.data, {
    type: '320k',
    url: 'https://cdn.example.com/song.mp3',
    headers: { Referer: 'https://www.example.com/' },
    userAgent: 'ExamplePlayer/1.0',
  })
})

test('musicUrl keeps legacy string responses compatible', async () => {
  const payload = await invokeMusicUrlResponse('https://cdn.example.com/legacy.mp3')

  assert.equal(payload.status, true)
  assert.deepEqual(payload.result.data, {
    type: '320k',
    url: 'https://cdn.example.com/legacy.mp3',
  })
})
