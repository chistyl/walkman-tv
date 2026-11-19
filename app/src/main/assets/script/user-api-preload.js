'use strict'

globalThis.lx_setup = (key, id, name, description, version, author, homepage, rawScript) => {
  delete globalThis.lx_setup

  // QuickJS does not provide the browser URLSearchParams global. Several lx sources build
  // signed query strings with it, so keep a small compatible implementation in the sandbox.
  if (typeof globalThis.URLSearchParams !== 'function') {
    const decode = value => decodeURIComponent(String(value).replace(/\+/g, ' '))
    const encode = value => encodeURIComponent(String(value)).replace(/%20/g, '+')
    globalThis.URLSearchParams = class URLSearchParams {
      #params = []

      constructor(init = '') {
        if (typeof init === 'string') {
          const query = init.startsWith('?') ? init.substring(1) : init
          if (query) {
            for (const part of query.split('&')) {
              const separator = part.indexOf('=')
              const name = separator < 0 ? part : part.substring(0, separator)
              const value = separator < 0 ? '' : part.substring(separator + 1)
              this.append(decode(name), decode(value))
            }
          }
        } else if (init && typeof init[Symbol.iterator] === 'function') {
          for (const entry of init) this.append(entry[0], entry[1])
        } else if (init && typeof init === 'object') {
          for (const name of Object.keys(init)) this.append(name, init[name])
        }
      }

      append(name, value) {
        this.#params.push([String(name), String(value)])
      }

      set(name, value) {
        name = String(name)
        value = String(value)
        let found = false
        for (let i = 0; i < this.#params.length; i++) {
          if (this.#params[i][0] !== name) continue
          if (!found) {
            this.#params[i][1] = value
            found = true
          } else {
            this.#params.splice(i--, 1)
          }
        }
        if (!found) this.#params.push([name, value])
      }

      get(name) {
        name = String(name)
        const entry = this.#params.find(item => item[0] === name)
        return entry ? entry[1] : null
      }

      getAll(name) {
        name = String(name)
        return this.#params.filter(item => item[0] === name).map(item => item[1])
      }

      has(name) {
        name = String(name)
        return this.#params.some(item => item[0] === name)
      }

      delete(name) {
        name = String(name)
        for (let i = this.#params.length - 1; i >= 0; i--) {
          if (this.#params[i][0] === name) this.#params.splice(i, 1)
        }
      }

      forEach(callback, thisArg) {
        for (let i = 0; i < this.#params.length; i++) {
          const [name, value] = this.#params[i]
          callback.call(thisArg, value, name, this)
        }
      }

      *entries() {
        for (let i = 0; i < this.#params.length; i++) {
          const [name, value] = this.#params[i]
          yield [name, value]
        }
      }

      [Symbol.iterator]() {
        return this.entries()
      }

      toString() {
        return this.#params.map(([name, value]) => `${encode(name)}=${encode(value)}`).join('&')
      }
    }
  }

  const _nativeCall = globalThis.__lx_native_call__
  delete globalThis.__lx_native_call__
  const checkLength = (str, length = 1048576) => {
    if (typeof str == 'string' && str.length > length) throw new Error('Input too long')
    return str
  }
  const nativeFuncNames = [
    '__lx_native_call__set_timeout',
    '__lx_native_call__utils_str2b64',
    '__lx_native_call__utils_b642buf',
    '__lx_native_call__utils_str2md5',
    '__lx_native_call__utils_random_bytes',
    '__lx_native_call__utils_aes_encrypt',
    '__lx_native_call__utils_rsa_encrypt',
  ]
  const nativeFuncs = {}
  for (const name of nativeFuncNames) {
    const nativeFunc = globalThis[name]
    delete globalThis[name]
    nativeFuncs[name.replace('__lx_native_call__', '')] = (...args) => {
      for (const arg of args) checkLength(arg)
      return nativeFunc(...args)
    }
  }
  // const set_timeout = globalThis.__lx_native_call__set_timeout
  // delete globalThis.__lx_native_call__set_timeout
  // const utils_str2b64 = globalThis.__lx_native_call__utils_str2b64
  // delete globalThis.__lx_native_call__utils_str2b64
  // const utils_b642buf = globalThis.__lx_native_call__utils_b642buf
  // delete globalThis.__lx_native_call__utils_b642buf
  // const utils_str2md5 = globalThis.__lx_native_call__utils_str2md5
  // delete globalThis.__lx_native_call__utils_str2md5
  // const utils_aes_encrypt = globalThis.__lx_native_call__utils_aes_encrypt
  // delete globalThis.__lx_native_call__utils_aes_encrypt
  // const utils_rsa_encrypt = globalThis.__lx_native_call__utils_rsa_encrypt
  // delete globalThis.__lx_native_call__utils_rsa_encrypt
  const KEY_PREFIX = {
    publicKeyStart: '-----BEGIN PUBLIC KEY-----',
    publicKeyEnd: '-----END PUBLIC KEY-----',
    privateKeyStart: '-----BEGIN PRIVATE KEY-----',
    privateKeyEnd: '-----END PRIVATE KEY-----',
  }
  const RSA_PADDING = {
    OAEPWithSHA1AndMGF1Padding: 'RSA/ECB/OAEPWithSHA1AndMGF1Padding',
    NoPadding: 'RSA/ECB/NoPadding',
  }
  const AES_MODE = {
    CBC_128_PKCS7Padding: 'AES/CBC/PKCS7Padding',
    ECB_128_NoPadding: 'AES',
  }
  const nativeCall = (action, data) => {
    data = JSON.stringify(data)
    // console.log('nativeCall', action, data)
    checkLength(data, 2097152)
    _nativeCall(key, action, data)
  }

  const callbacks = new Map()
  let timeoutId = 0
  const _setTimeout = (callback, timeout = 0, ...params) => {
    if (typeof callback !== 'function') throw new Error('callback required a function')
    if (typeof timeout !== 'number' || timeout < 0) throw new Error('timeout required a number')
    if (timeoutId > 90000000000) throw new Error('max timeout')
    const id = timeoutId++
    callbacks.set(id, {
      callback(...args) {
        // eslint-disable-next-line n/no-callback-literal
        callback(...args)
      },
      params,
    })
    nativeFuncs.set_timeout(id, parseInt(timeout))
    return id
  }
  const _clearTimeout = (id) => {
    const tagret = callbacks.get(id)
    if (!tagret) return
    callbacks.delete(id)
  }
  const handleSetTimeout = (id) => {
    const tagret = callbacks.get(id)
    if (!tagret) return
    callbacks.delete(id)
    tagret.callback(...tagret.params)
  }

  // UTF-8 codec compatible with Node Buffer semantics, including surrogate pairs.
  // Invalid byte sequences decode to U+FFFD instead of reading beyond the input.
  function bytesToString(input) {
    const bytes = Array.from(input, value => Number(value) & 0xff)
    let result = ''
    for (let i = 0; i < bytes.length;) {
      const a = bytes[i++]
      let codePoint
      if (a < 0x80) codePoint = a
      else if (a >= 0xc2 && a <= 0xdf && i < bytes.length &&
        (bytes[i] & 0xc0) === 0x80) {
        codePoint = ((a & 0x1f) << 6) | (bytes[i++] & 0x3f)
      } else if (a >= 0xe0 && a <= 0xef && i + 1 < bytes.length &&
        (bytes[i] & 0xc0) === 0x80 && (bytes[i + 1] & 0xc0) === 0x80 &&
        !(a === 0xe0 && bytes[i] < 0xa0) && !(a === 0xed && bytes[i] >= 0xa0)) {
        codePoint = ((a & 0x0f) << 12) | ((bytes[i++] & 0x3f) << 6) | (bytes[i++] & 0x3f)
      } else if (a >= 0xf0 && a <= 0xf4 && i + 2 < bytes.length &&
        (bytes[i] & 0xc0) === 0x80 && (bytes[i + 1] & 0xc0) === 0x80 &&
        (bytes[i + 2] & 0xc0) === 0x80 &&
        !(a === 0xf0 && bytes[i] < 0x90) && !(a === 0xf4 && bytes[i] > 0x8f)) {
        codePoint = ((a & 0x07) << 18) | ((bytes[i++] & 0x3f) << 12) |
          ((bytes[i++] & 0x3f) << 6) | (bytes[i++] & 0x3f)
      } else codePoint = 0xfffd
      result += String.fromCodePoint(codePoint)
    }
    return result
  }

  function stringToBytes(inputString) {
    const bytes = []
    for (let i = 0; i < inputString.length; i++) {
      let codePoint = inputString.codePointAt(i)
      if (codePoint > 0xffff) i++
      else if (codePoint >= 0xd800 && codePoint <= 0xdfff) codePoint = 0xfffd
      if (codePoint < 0x80) bytes.push(codePoint)
      else if (codePoint < 0x800) {
        bytes.push(0xc0 | (codePoint >> 6), 0x80 | (codePoint & 0x3f))
      } else if (codePoint < 0x10000) {
        bytes.push(0xe0 | (codePoint >> 12), 0x80 | ((codePoint >> 6) & 0x3f), 0x80 | (codePoint & 0x3f))
      } else {
        bytes.push(0xf0 | (codePoint >> 18), 0x80 | ((codePoint >> 12) & 0x3f),
          0x80 | ((codePoint >> 6) & 0x3f), 0x80 | (codePoint & 0x3f))
      }
    }
    return bytes
  }

  // QuickJS builds may omit WHATWG text codecs. Provide the UTF-8 surface used by
  // community scripts; keep the implementation deliberately limited to UTF-8.
  if (typeof globalThis.TextEncoder !== 'function') {
    globalThis.TextEncoder = class TextEncoder {
      get encoding() { return 'utf-8' }
      encode(input = '') { return new Uint8Array(stringToBytes(String(input))) }
      encodeInto(input, destination) {
        const bytes = stringToBytes(String(input))
        let read = 0, written = 0
        while (read < String(input).length) {
          const codePoint = String(input).codePointAt(read)
          const charCount = codePoint > 0xffff ? 2 : 1
          const nextBytes = stringToBytes(String(input).slice(read, read + charCount))
          if (written + nextBytes.length > destination.length) break
          destination.set(nextBytes, written)
          written += nextBytes.length
          read += charCount
        }
        return { read, written }
      }
    }
  }

  if (typeof globalThis.TextDecoder !== 'function') {
    globalThis.TextDecoder = class TextDecoder {
      constructor(label = 'utf-8', options = {}) {
        const normalized = String(label).trim().toLowerCase()
        if (!['utf-8', 'utf8'].includes(normalized)) throw new RangeError('Only UTF-8 is supported')
        this.encoding = 'utf-8'
        this.fatal = options.fatal === true
        this.ignoreBOM = options.ignoreBOM === true
      }
      decode(input = new Uint8Array(), options = {}) {
        if (options.stream) throw new TypeError('Streaming decode is not supported')
        let bytes
        if (input instanceof ArrayBuffer) bytes = new Uint8Array(input)
        else if (ArrayBuffer.isView(input)) {
          bytes = new Uint8Array(input.buffer, input.byteOffset, input.byteLength)
        } else if (input == null) bytes = new Uint8Array()
        else throw new TypeError('Expected an ArrayBuffer or ArrayBufferView')
        const decoded = bytesToString(bytes)
        if (this.fatal && !stringToBytes(decoded).every((value, index) => value === bytes[index]) ||
          (this.fatal && stringToBytes(decoded).length !== bytes.length)) {
          throw new TypeError('The encoded data was not valid UTF-8')
        }
        return !this.ignoreBOM && decoded.charCodeAt(0) === 0xfeff ? decoded.slice(1) : decoded
      }
    }
  }

  const BASE64_ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'
  function bytesToBase64(input) {
    const bytes = Array.from(input, value => Number(value) & 0xff)
    let result = ''
    for (let i = 0; i < bytes.length; i += 3) {
      const a = bytes[i], hasB = i + 1 < bytes.length, hasC = i + 2 < bytes.length
      const b = hasB ? bytes[i + 1] : 0
      const c = hasC ? bytes[i + 2] : 0
      result += BASE64_ALPHABET[a >> 2]
      result += BASE64_ALPHABET[((a & 3) << 4) | (b >> 4)]
      result += hasB ? BASE64_ALPHABET[((b & 15) << 2) | (c >> 6)] : '='
      result += hasC ? BASE64_ALPHABET[c & 63] : '='
    }
    return result
  }

  const NATIVE_EVENTS_NAMES = {
    init: 'init',
    showUpdateAlert: 'showUpdateAlert',
    request: 'request',
    cancelRequest: 'cancelRequest',
    response: 'response',
    // 'utils.crypto.aesEncrypt': 'utils.crypto.aesEncrypt',
    // 'utils.crypto.rsaEncrypt': 'utils.crypto.rsaEncrypt',
    // 'utils.crypto.randomBytes': 'utils.crypto.randomBytes',
    // 'utils.crypto.md5': 'utils.crypto.md5',
    // 'utils.buffer.from': 'utils.buffer.from',
    // 'utils.buffer.bufToString': 'utils.buffer.bufToString',
    // 'utils.zlib.inflate': 'utils.zlib.inflate',
    // 'utils.zlib.deflate': 'utils.zlib.deflate',
  }
  const EVENT_NAMES = {
    request: 'request',
    inited: 'inited',
    updateAlert: 'updateAlert',
  }
  const eventNames = Object.values(EVENT_NAMES)
  const events = {
    request: null,
  }
  // The script, not the client, declares platform identifiers. Keep the legacy
  // platform defaults for old lx-music v4 scripts, but accept additional safe keys.
  // Source identifiers are passed through unchanged to the native resolver.
  const legacyQualitys = ['128k', '320k', 'flac', 'flac24bit', 'hires', 'atmos', 'atmos_plus', 'master']
  // Keep the legacy actions and explicitly supported TV extensions. Action names
  // are still allow-listed so arbitrary script declarations cannot create host actions.
  const legacyActions = ['musicUrl', 'lyric', 'pic', 'search', 'songlist']
  const validSourceKey = key => typeof key === 'string' && /^[a-zA-Z0-9_-]{1,64}$/.test(key)
  const normalizeSourceCapability = (key, value) => {
    if (!validSourceKey(key) || !value || value.type !== 'music') return null
    if (!Array.isArray(value.actions) || !Array.isArray(value.qualitys)) return null
    const actions = [...new Set(value.actions.filter(action =>
      typeof action === 'string' && legacyActions.includes(action),
    ))]
    // Known local source remains metadata-only. Custom quality identifiers share the
    // host's 1..64 character contract so they are not silently truncated at this bridge.
    const qualitys = key === 'local'
      ? []
      : [...new Set(value.qualitys.filter(q =>
        typeof q === 'string' && /^[a-zA-Z0-9_-]{1,64}$/.test(q),
      ))]
    return { type: 'music', actions, qualitys }
  }

  const verifyLyricInfo = (info) => {
    if (typeof info != 'object' || typeof info.lyric != 'string') throw new Error('failed')
    if (info.lyric.length > 51200) throw new Error('failed')
    return {
      lyric: info.lyric,
      tlyric: (typeof info.tlyric == 'string' && info.tlyric.length < 5120) ? info.tlyric : null,
      rlyric: (typeof info.rlyric == 'string' && info.rlyric.length < 5120) ? info.rlyric : null,
      lxlyric: (typeof info.lxlyric == 'string' && info.lxlyric.length < 8192) ? info.lxlyric : null,
    }
  }

  const requestQueue = new Map()
  let isInitedApi = false
  let isShowedUpdateAlert = false

  const sendNativeRequest = (url, options, callback) => {
    const requestKey = Math.random().toString()
    const requestInfo = {
      aborted: false,
      abort: () => {
        nativeCall(NATIVE_EVENTS_NAMES.cancelRequest, requestKey)
      },
    }
    requestQueue.set(requestKey, {
      callback,
      // timeout: setTimeout(() => {
      //   const req = requestQueue.get(requestKey)
      //   if (req) req.timeout = null
      //   nativeCall(NATIVE_EVENTS_NAMES.cancelRequest, requestKey)
      // }, 30000),
      requestInfo,
    })

    nativeCall(NATIVE_EVENTS_NAMES.request, { requestKey, url, options })
    return requestInfo
  }
  const handleNativeResponse = ({ requestKey, error, response }) => {
    const targetRequest = requestQueue.get(requestKey)
    if (!targetRequest) return
    requestQueue.delete(requestKey)
    targetRequest.requestInfo.aborted = true
    // if (targetRequest.timeout) clearTimeout(targetRequest.timeout)
    if (error == null) targetRequest.callback(null, response)
    else targetRequest.callback(new Error(error), null)
  }

  const handleRequest = ({ requestKey, data }) => {
    // console.log(data)
    if (!events.request) return nativeCall(NATIVE_EVENTS_NAMES.response, { requestKey, status: false, errorMessage: 'Request event is not defined' })
    try {
      events.request.call(globalThis.lx, { source: data.source, action: data.action, info: data.info }).then(response => {
        let result
        switch (data.action) {
          case 'musicUrl':
            if (typeof response != 'string' || response.length > 2048 || !/^https?:/.test(response)) throw new Error('failed')
            result = {
              source: data.source,
              action: data.action,
              data: {
                type: data.info.type,
                url: response,
              },
            }
            break
          case 'lyric':
            result = {
              source: data.source,
              action: data.action,
              data: verifyLyricInfo(response),
            }
            break
          case 'pic':
            if (typeof response != 'string' || response.length > 2048 || !/^https?:/.test(response)) throw new Error('failed')
            result = {
              source: data.source,
              action: data.action,
              data: response,
            }
            break
          default:
            // Search and songlist are structured provider APIs, not URL/lyric payloads.
            // Return their data unchanged for Kotlin's compatibility parsers.
            result = response
            break
        }
        nativeCall(NATIVE_EVENTS_NAMES.response, { requestKey, status: true, result })
      }).catch(err => {
        // console.log('handleRequest err', err)
        nativeCall(NATIVE_EVENTS_NAMES.response, { requestKey, status: false, errorMessage: err.message })
      })
    } catch (err) {
      // console.log('handleRequest call err', err)
      nativeCall(NATIVE_EVENTS_NAMES.response, { requestKey, status: false, errorMessage: err.message })
    }
  }

  const jsCall = (action, data) => {
    // console.log('jsCall', action, data)
    switch (action) {
      case '__run_error__':
        if (!isInitedApi) isInitedApi = true
        return
      case '__set_timeout__':
        handleSetTimeout(data)
        return
      case 'request':
        handleRequest(data)
        return
      case 'response':
        handleNativeResponse(data)
        return
    }
    return 'Unknown action: ' + action
  }

  Object.defineProperty(globalThis, '__lx_native__', {
    enumerable: false,
    configurable: false,
    writable: false,
    value: (_key, action, data) => {
      if (key != _key) return 'Invalid key'
      return data == null ? jsCall(action) : jsCall(action, JSON.parse(data))
    },
  })


  /**
   *
   * @param {*} info {
   *                    sources: {
   *                         kw: ['128k', '320k', 'flac', 'flac24bit'],
   *                         kg: ['128k', '320k', 'flac', 'flac24bit'],
   *                         tx: ['128k', '320k', 'flac', 'flac24bit'],
   *                         wy: ['128k', '320k', 'flac', 'flac24bit'],
   *                         mg: ['128k', '320k', 'flac', 'flac24bit'],
   *                     }
   *                 }
   */
  const handleInit = (info) => {
    if (!info) {
      nativeCall(NATIVE_EVENTS_NAMES.init, { info: null, status: false, errorMessage: 'Missing required parameter init info' })
      // sendMessage(NATIVE_EVENTS_NAMES.init, false, null, typeof info.message === 'string' ? info.message.substring(0, 100) : '')
      return
    }
    // if (!info.status) {
    //   nativeCall(NATIVE_EVENTS_NAMES.init, { info: null, status: false, errorMessage: 'Init failed' })
    //   // sendMessage(NATIVE_EVENTS_NAMES.init, false, null, typeof info.message === 'string' ? info.message.substring(0, 100) : '')
    //   return
    // }
    const sourceInfo = {
      sources: {},
    }
    try {
      if (typeof info.sources !== 'object' || Array.isArray(info.sources)) {
        throw new Error('sources must be an object')
      }
      for (const source of Object.keys(info.sources)) {
        const capability = normalizeSourceCapability(source, info.sources[source])
        if (capability && (capability.actions.length || capability.qualitys.length)) {
          sourceInfo.sources[source] = capability
        }
      }
    } catch (error) {
      // console.log(error)
      nativeCall(NATIVE_EVENTS_NAMES.init, { info: null, status: false, errorMessage: error.message })
      return
    }
    nativeCall(NATIVE_EVENTS_NAMES.init, { info: sourceInfo, status: true })
  }
  // App-level self-updating is intentionally disabled in the TV build.
  // Keep the lx-music API callable so imported community scripts do not fail,
  // but silently acknowledge update notices without opening an installer flow.
  const handleShowUpdateAlert = (_data, resolve) => {
    resolve()
  }

  const dataToB64 = (data) => {
    if (typeof data === 'string') return nativeFuncs.utils_str2b64(data)
    else if (Array.isArray(data) || ArrayBuffer.isView(data)) return utils.buffer.bufToString(data, 'base64')
    throw new Error('data type error: ' + typeof data + ' raw data: ' + data)
  }
  const utils = {
    crypto: {
      aesEncrypt(buffer, mode, key, iv) {
        // console.log('aesEncrypt', buffer, mode, key, iv)
        switch (mode) {
          case 'aes-128-cbc':
            return utils.buffer.from(nativeFuncs.utils_aes_encrypt(dataToB64(buffer), dataToB64(key), dataToB64(iv), AES_MODE.CBC_128_PKCS7Padding), 'base64')
          case 'aes-128-ecb':
            return utils.buffer.from(nativeFuncs.utils_aes_encrypt(dataToB64(buffer), dataToB64(key), '', AES_MODE.ECB_128_NoPadding), 'base64')
          default:
            throw new Error('Binary encoding is not supported for input strings')
        }
      },
      rsaEncrypt(buffer, key) {
        // console.log('rsaEncrypt', buffer, key)
        if (typeof key !== 'string') throw new Error('Invalid RSA key')
        key = key.replace(KEY_PREFIX.publicKeyStart, '')
          .replace(KEY_PREFIX.publicKeyEnd, '')
        return utils.buffer.from(nativeFuncs.utils_rsa_encrypt(dataToB64(buffer), key, RSA_PADDING.NoPadding), 'base64')
      },
      randomBytes(size) {
        if (!Number.isSafeInteger(size) || size < 0 || size > 65536) {
          throw new RangeError('size must be an integer between 0 and 65536')
        }
        return new Uint8Array(JSON.parse(nativeFuncs.utils_random_bytes(size)))
      },
      md5(str) {
        if (typeof str !== 'string') throw new Error('param required a string')
        const md5 = nativeFuncs.utils_str2md5(encodeURIComponent(str))
        // console.log('md5', str, md5)
        return md5
      },
    },
    buffer: {
      from(input, encoding) {
        // console.log('buffer.from', input, encoding)
        if (typeof input === 'string') {
          switch ((encoding || 'utf8').toLowerCase()) {
            case 'base64':
              return new Uint8Array(JSON.parse(nativeFuncs.utils_b642buf(input)))
            case 'base64url': {
              const normalized = input.replace(/-/g, '+').replace(/_/g, '/')
              return new Uint8Array(JSON.parse(nativeFuncs.utils_b642buf(normalized)))
            }
            case 'hex':
              if (input.length % 2 !== 0 || !/^[0-9a-f]*$/i.test(input)) throw new Error('Invalid hex string')
              return new Uint8Array(input.match(/.{2}/g)?.map(byte => parseInt(byte, 16)) || [])
            case 'binary':
            case 'latin1':
              return new Uint8Array(Array.from(input, char => char.charCodeAt(0) & 0xff))
            case 'ascii':
              return new Uint8Array(Array.from(input, char => char.charCodeAt(0) & 0x7f))
            case 'ucs2':
            case 'ucs-2':
            case 'utf16le':
            case 'utf-16le': {
              const bytes = []
              for (let i = 0; i < input.length; i++) {
                const code = input.charCodeAt(i)
                bytes.push(code & 0xff, code >> 8)
              }
              return new Uint8Array(bytes)
            }
            default:
              return new Uint8Array(stringToBytes(input))
          }
        } else if (Array.isArray(input) || ArrayBuffer.isView(input)) {
          return new Uint8Array(Array.from(input, value => Number(value) & 0xff))
        } else if (input instanceof ArrayBuffer) {
          return new Uint8Array(input.slice(0))
        } else {
          throw new Error('Unsupported input type: ' + input + ' encoding: ' + encoding)
        }
      },
      bufToString(buf, format) {
        // console.log('buffer.bufToString', buf, format)
        if (Array.isArray(buf) || ArrayBuffer.isView(buf)) {
          switch (format) {
            case 'binary':
              // return new TextDecoder('latin1').decode(new Uint8Array(buf))
              return buf
            case 'hex':
              return new Uint8Array(buf).reduce((str, byte) => str + byte.toString(16).padStart(2, '0'), '')
            case 'base64':
              // Encode raw bytes directly; decoding bytes as UTF-8 first corrupts binary payloads.
              return bytesToBase64(buf)
            case 'utf8':
            case 'utf-8':
            default:
              return bytesToString(Array.from(buf))
          }
        } else {
          throw new Error('Input is not a valid buffer: ' + buf + ' format: ' + format)
        }
      },
    },
    // zlib: {
    //   inflate(buf) {
    //     return new Promise((resolve, reject) => {
    //       zlib.inflate(buf, (err, data) => {
    //         if (err) reject(new Error(err.message))
    //         else resolve(data)
    //       })
    //     })
    //   },
    //   deflate(data) {
    //     return new Promise((resolve, reject) => {
    //       zlib.deflate(data, (err, buf) => {
    //         if (err) reject(new Error(err.message))
    //         else resolve(buf)
    //       })
    //     })
    //   },
    // }),
  }

  globalThis.lx = {
    EVENT_NAMES,
    request(url, { method = 'get', timeout, headers, body, form, formData, binary }, callback) {
      let options = { headers, binary: binary === true }
      // let data
      // if (body) {
      //   data = body
      // } else if (form) {
      //   data = form
      //   // data.content_type = 'application/x-www-form-urlencoded'
      //   options.json = false
      // } else if (formData) {
      //   data = formData
      //   // data.content_type = 'multipart/form-data'
      //   options.json = false
      // }
      if (timeout && typeof timeout == 'number' && timeout > 0) options.timeout = Math.min(timeout, 60_000)

      let request = sendNativeRequest(url, { method, body, form, formData, ...options }, (err, resp) => {
        if (err) {
          callback(err, null, null)
        } else {
          callback(err, {
            statusCode: resp.statusCode,
            statusMessage: resp.statusMessage,
            headers: resp.headers,
            // bytes: resp.bytes,
            // raw: resp.raw,
            body: resp.body,
          }, resp.body)
        }
      })

      return () => {
        if (!request.aborted) request.abort()
        request = null
      }
    },
    send(eventName, data) {
      return new Promise((resolve, reject) => {
        if (!eventNames.includes(eventName)) return reject(new Error('The event is not supported: ' + eventName))
        switch (eventName) {
          case EVENT_NAMES.inited:
            if (isInitedApi) return reject(new Error('Script is inited'))
            isInitedApi = true
            handleInit(data)
            resolve()
            break
          case EVENT_NAMES.updateAlert:
            if (isShowedUpdateAlert) return reject(new Error('The update alert can only be called once.'))
            isShowedUpdateAlert = true
            handleShowUpdateAlert(data, resolve, reject)
            break
          default:
            reject(new Error('Unknown event name: ' + eventName))
        }
      })
    },
    on(eventName, handler) {
      if (!eventNames.includes(eventName)) return Promise.reject(new Error('The event is not supported: ' + eventName))
      switch (eventName) {
        case EVENT_NAMES.request:
          events.request = handler
          break
        default: return Promise.reject(new Error('The event is not supported: ' + eventName))
      }
      return Promise.resolve()
    },
    utils,
    currentScriptInfo: {
      name,
      description,
      version,
      author,
      homepage,
      rawScript,
    },
    version: '2.0.0',
    env: 'mobile',
  }

  globalThis.setTimeout = _setTimeout
  globalThis.clearTimeout = _clearTimeout

  const freezeObject = (obj) => {
    if (typeof obj != 'object') return
    Object.freeze(obj)
    for (const subObj of Object.values(obj)) freezeObject(subObj)
  }
  freezeObject(globalThis.lx)

  const _toString = Function.prototype.toString
  // eslint-disable-next-line no-extend-native
  Function.prototype.toString = function() {
    return Object.getOwnPropertyDescriptors(this).name.configurable
      ? _toString.apply(this)
      : `function ${this.name}() { [native code] }`
  }
  // eslint-disable-next-line no-eval
  globalThis.eval = function() {
    throw new Error('eval is not available')
  }
  const proxyFunctionConstructor = new Proxy(Function.prototype.constructor, {
    apply() {
      throw new Error('Dynamic code execution is not allowed.')
    },
    construct() {
      throw new Error('Dynamic code execution is not allowed.')
    },
  })
  // eslint-disable-next-line no-extend-native
  Object.defineProperty(Function.prototype, 'constructor', {
    value: proxyFunctionConstructor,
    writable: false,
    configurable: false,
    enumerable: false,
  })
  globalThis.Function = proxyFunctionConstructor
  // globalThis.Function = function() {
  //   throw new Error('Function is not available')
  // }

  const excludes = [
    Function.prototype.toString,
    Function.prototype.toLocaleString,
    Object.prototype.toString,
  ]
  const freezeObjectProperty = (obj, freezedObj = new Set()) => {
    if (obj == null) return
    switch (typeof obj) {
      case 'object':
      case 'function':
        if (freezedObj.has(obj)) return
        // Object.freeze(obj)
        freezedObj.add(obj)
        for (const [name, { ...config }] of Object.entries(Object.getOwnPropertyDescriptors(obj))) {
          if (!excludes.includes(config.value)) {
            if (config.writable) config.writable = false
            if (config.configurable) config.configurable = false
            Object.defineProperty(obj, name, config)
          }
          freezeObjectProperty(config.value, freezedObj)
        }
    }
  }
  freezeObjectProperty(globalThis)

  console.log('Preload finished.')
}
