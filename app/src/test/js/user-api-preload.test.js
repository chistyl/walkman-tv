const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('node:vm')

const preload = fs.readFileSync(
  path.resolve(__dirname, '../../main/assets/script/user-api-preload.js'),
  'utf8',
)

function loadPreloadWithoutNativeUrlSearchParams(nativeOverrides = {}) {
  const nativeCalls = []
  const context = vm.createContext({
    URLSearchParams: undefined,
    __lx_native_call__: (_key, action, data) => nativeCalls.push({ action, data: JSON.parse(data) }),
    __lx_native_call__set_timeout: () => {},
    __lx_native_call__utils_str2b64: value => Buffer.from(value, 'utf8').toString('base64'),
    __lx_native_call__utils_b642buf: value => JSON.stringify(Array.from(Buffer.from(value, 'base64'))),
    __lx_native_call__utils_str2md5: () => '',
    __lx_native_call__utils_random_bytes: size => JSON.stringify(Array.from({ length: size }, (_, index) => (index * 37 + 11) & 0xff)),
    __lx_native_call__utils_aes_encrypt: () => '',
    __lx_native_call__utils_rsa_encrypt: () => '',
    console: { log() {}, info() {}, warn() {}, error() {} },
  })
  Object.assign(context, nativeOverrides)
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



test('buffer codec preserves four-byte UTF-8 and arbitrary binary Base64', () => {
  const { context } = loadPreloadWithoutNativeUrlSearchParams()
  const result = vm.runInContext(`(() => {
    const text = '繁體中文🎵𠜎'
    const encoded = lx.utils.buffer.from(text)
    const decoded = lx.utils.buffer.bufToString(encoded, 'utf8')
    const binary = new Uint8Array([0, 127, 128, 255, 1, 254])
    const base64 = lx.utils.buffer.bufToString(binary, 'base64')
    const restored = lx.utils.buffer.from(base64, 'base64')
    const hex = lx.utils.buffer.from('00ff80', 'hex')
    let invalidHexRejected = false
    try { lx.utils.buffer.from('0xz', 'hex') } catch (_) { invalidHexRejected = true }
    return JSON.stringify({
      decoded,
      encodedBytes: Array.from(encoded),
      base64,
      restoredBytes: Array.from(restored),
      hexBytes: Array.from(hex),
      invalidHexRejected,
    })
  })()`, context)

  assert.deepEqual(JSON.parse(result), {
    decoded: '繁體中文🎵𠜎',
    encodedBytes: Array.from(Buffer.from('繁體中文🎵𠜎', 'utf8')),
    base64: 'AH+A/wH+',
    restoredBytes: [0, 127, 128, 255, 1, 254],
    hexBytes: [0, 255, 128],
    invalidHexRejected: true,
  })
})

test('TextEncoder and TextDecoder polyfills support UTF-8 and binary views', () => {
  const { context } = loadPreloadWithoutNativeUrlSearchParams()
  const result = vm.runInContext(`(() => {
    const text = '繁體中文🎵𠜎'
    const encoder = new TextEncoder()
    const encoded = encoder.encode(text)
    const backing = new Uint8Array([99, ...encoded, 88])
    const decoder = new TextDecoder('utf-8')
    const decoded = decoder.decode(new DataView(backing.buffer, 1, encoded.length))
    const target = new Uint8Array(7)
    const progress = encoder.encodeInto('A🎵B', target)
    const bom = decoder.decode(new Uint8Array([0xef, 0xbb, 0xbf, 0x41]))
    let fatalRejected = false
    try {
      new TextDecoder('utf-8', { fatal: true }).decode(new Uint8Array([0xc3, 0x28]))
    } catch (_) { fatalRejected = true }
    return JSON.stringify({
      encoding: encoder.encoding,
      decoded,
      encodedMatches: Array.from(encoded).join(',') === Array.from(lx.utils.buffer.from(text)).join(','),
      progress,
      target: Array.from(target),
      bom,
      fatalRejected,
    })
  })()`, context)

  assert.deepEqual(JSON.parse(result), {
    encoding: 'utf-8',
    decoded: '繁體中文🎵𠜎',
    encodedMatches: true,
    progress: { read: 4, written: 6 },
    target: [65, 240, 159, 142, 181, 66, 0],
    bom: 'A',
    fatalRejected: true,
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

test('buffer.from accepts ArrayBuffer, typed arrays and common Node encodings', () => {
  const { context } = loadPreloadWithoutNativeUrlSearchParams()
  const result = vm.runInContext(`(() => {
    const source = new Uint8Array([0, 127, 128, 255])
    const arrayBuffer = lx.utils.buffer.from(source.buffer)
    const typedArray = lx.utils.buffer.from(new Uint16Array([1, 255, 256]))
    const latin = lx.utils.buffer.from('Aÿ', 'latin1')
    const utf16 = lx.utils.buffer.from('A🎵', 'utf16le')
    const urlSafe = lx.utils.buffer.from('-_8', 'base64url')
    return JSON.stringify({
      arrayBuffer: Array.from(arrayBuffer),
      typedArray: Array.from(typedArray),
      latin: Array.from(latin),
      utf16: Array.from(utf16),
      urlSafe: Array.from(urlSafe),
    })
  })()`, context)

  assert.deepEqual(JSON.parse(result), {
    arrayBuffer: [0, 127, 128, 255],
    typedArray: [1, 255, 0],
    latin: [65, 255],
    utf16: [65, 0, 61, 216, 84, 220],
    urlSafe: [251, 255],
  })
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


test('crypto randomBytes uses the native secure random bridge and validates sizes', () => {
  const { context } = loadPreloadWithoutNativeUrlSearchParams()
  const result = vm.runInContext(`(() => {
    const bytes = lx.utils.crypto.randomBytes(8)
    let negativeRejected = false
    let fractionalRejected = false
    let oversizedRejected = false
    try { lx.utils.crypto.randomBytes(-1) } catch (_) { negativeRejected = true }
    try { lx.utils.crypto.randomBytes(1.5) } catch (_) { fractionalRejected = true }
    try { lx.utils.crypto.randomBytes(65537) } catch (_) { oversizedRejected = true }
    return JSON.stringify({ bytes: Array.from(bytes), negativeRejected, fractionalRejected, oversizedRejected })
  })()`, context)

  assert.deepEqual(JSON.parse(result), {
    bytes: [11, 48, 85, 122, 159, 196, 233, 14],
    negativeRejected: true,
    fractionalRejected: true,
    oversizedRejected: true,
  })
})


test('crypto helpers preserve legacy MD5, AES mode and RSA key contracts', () => {
  const nativeCalls = []
  const { context } = loadPreloadWithoutNativeUrlSearchParams({
    __lx_native_call__utils_str2md5: value => { nativeCalls.push(['md5', value]); return 'md5-result' },
    __lx_native_call__utils_aes_encrypt: (...args) => { nativeCalls.push(['aes', ...args]); return 'AQID' },
    __lx_native_call__utils_rsa_encrypt: (...args) => { nativeCalls.push(['rsa', ...args]); return 'BAUG' },
  })
  const result = vm.runInContext(`(() => {
    const aesCbc = Array.from(lx.utils.crypto.aesEncrypt(new Uint8Array([1, 2]), 'aes-128-cbc', new Uint8Array(16), new Uint8Array(16)))
    const aesEcb = Array.from(lx.utils.crypto.aesEncrypt(new Uint8Array([3]), 'aes-128-ecb', new Uint8Array(16)))
    const rsa = Array.from(lx.utils.crypto.rsaEncrypt(new Uint8Array([4]), '-----BEGIN PUBLIC KEY-----\\nQUJD\\n-----END PUBLIC KEY-----'))
    const md5 = lx.utils.crypto.md5('a+b c')
    return JSON.stringify({ aesCbc, aesEcb, rsa, md5 })
  })()`, context)

  assert.deepEqual(JSON.parse(result), {
    aesCbc: [1, 2, 3], aesEcb: [1, 2, 3], rsa: [4, 5, 6], md5: 'md5-result',
  })
  assert.deepEqual(nativeCalls, [
    ['aes', 'AQI=', 'AAAAAAAAAAAAAAAAAAAAAA==', 'AAAAAAAAAAAAAAAAAAAAAA==', 'AES/CBC/PKCS7Padding'],
    ['aes', 'Aw==', 'AAAAAAAAAAAAAAAAAAAAAA==', '', 'AES'],
    ['rsa', 'BA==', '\nQUJD\n', 'RSA/ECB/NoPadding'],
    ['md5', 'a%2Bb%20c'],
  ])
})
