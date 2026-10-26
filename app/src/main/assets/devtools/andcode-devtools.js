/*
 * AndCode DevTools page agent.
 *
 * Runs in every frame of a page shown in AndCode's browser, before the page's own scripts when the
 * WebView supports document-start scripts. It records console output, uncaught errors, fetch/XHR
 * traffic and resource timings, and posts them to the app through the `__andcodeDevtoolsBridge`
 * message port the app registers. The app reads the DOM, storage and timings, and evaluates code,
 * through the functions on `window.__andcodeDT`.
 *
 * It must never break the page: every hook is wrapped so a failure here is swallowed, originals are
 * always called, and nothing is posted when the bridge is missing.
 */
(function () {
  'use strict';
  if (window.__andcodeDT) return;

  var bridge = window.__andcodeDevtoolsBridge;
  var MAX_STRING = 4000;
  var MAX_BODY = 64 * 1024;
  var MAX_KEYS = 80;
  var MAX_DEPTH = 3;
  var isTop = window === window.top;
  var frame = isTop ? '' : String(location.href);

  function now() {
    return Date.now();
  }

  // Messages go out in batches: a page logging in a tight loop would otherwise wake the app's UI
  // thread once per line. Each batch is capped, and what does not fit waits for the next flush.
  var queue = [];
  var flushing = false;
  var MAX_BATCH = 200;
  var MAX_QUEUE = 2000;

  function flush() {
    flushing = false;
    if (!queue.length) return;
    var batch = queue.splice(0, MAX_BATCH);
    try {
      bridge.postMessage(JSON.stringify({ t: 'batch', items: batch }));
    } catch (e) {
      // A batch that cannot be serialized is dropped rather than breaking the page.
    }
    if (queue.length) schedule();
  }

  // Leaving the page would drop what is still queued; send all of it now, as Preserve log keeps it.
  function flushAll() {
    while (queue.length) flush();
  }

  function schedule() {
    if (flushing) return;
    flushing = true;
    setTimeout(flush, 50);
  }

  function post(message) {
    if (!bridge) return;
    // The first message of a document goes at once, so the app resets its log before anything else.
    if (message.t === 'ready') {
      try {
        bridge.postMessage(JSON.stringify(message));
      } catch (e) {}
      return;
    }
    if (queue.length >= MAX_QUEUE) return;
    queue.push(message);
    schedule();
  }

  function clip(text, max) {
    text = String(text);
    return text.length > max ? text.slice(0, max) + '… (' + text.length + ' chars)' : text;
  }

  function describeNode(node) {
    if (!node || !node.nodeType) return String(node);
    if (node.nodeType === 3) return '#text ' + JSON.stringify(clip(node.textContent || '', 80));
    if (node.nodeType === 9) return '#document';
    if (node.nodeType !== 1) return node.nodeName;
    var label = node.tagName.toLowerCase();
    if (node.id) label += '#' + node.id;
    if (typeof node.className === 'string' && node.className.trim()) {
      label += '.' + node.className.trim().split(/\s+/).slice(0, 3).join('.');
    }
    return label;
  }

  /*
   * A value turned into JSON the app can show as a tree. Plain JSON values pass through; anything
   * JSON cannot hold becomes `{"__t": kind, ...}` so the app can label it.
   */
  function serialize(value, depth, seen) {
    depth = depth || 0;
    seen = seen || [];
    try {
      if (value === null) return null;
      var type = typeof value;
      if (type === 'undefined') return { __t: 'undefined' };
      if (type === 'string') return clip(value, MAX_STRING);
      if (type === 'number') {
        if (!isFinite(value)) return { __t: 'number', v: String(value) };
        return value;
      }
      if (type === 'boolean') return value;
      if (type === 'bigint') return { __t: 'bigint', v: value.toString() + 'n' };
      if (type === 'symbol') return { __t: 'symbol', v: value.toString() };
      if (type === 'function') {
        return { __t: 'function', v: 'ƒ ' + (value.name || 'anonymous') + '()' };
      }
      if (value instanceof Error) {
        return { __t: 'error', v: String(value.name) + ': ' + String(value.message), stack: clip(value.stack || '', MAX_STRING) };
      }
      if (typeof Node !== 'undefined' && value instanceof Node) return { __t: 'node', v: describeNode(value) };
      if (value instanceof Date) return { __t: 'date', v: isNaN(value) ? 'Invalid Date' : value.toISOString() };
      if (value instanceof RegExp) return { __t: 'regexp', v: String(value) };
      if (typeof Promise !== 'undefined' && value instanceof Promise) return { __t: 'promise', v: 'Promise' };
      if (seen.indexOf(value) >= 0) return { __t: 'circular', v: '[Circular]' };
      if (depth >= MAX_DEPTH) {
        return { __t: 'more', v: Array.isArray(value) ? 'Array(' + value.length + ')' : (value.constructor && value.constructor.name) || 'Object' };
      }
      seen = seen.concat([value]);
      if (typeof Map !== 'undefined' && value instanceof Map) {
        var map = {};
        var i = 0;
        value.forEach(function (v, k) {
          if (i++ < MAX_KEYS) map[String(k)] = serialize(v, depth + 1, seen);
        });
        return { __t: 'map', size: value.size, entries: map };
      }
      if (typeof Set !== 'undefined' && value instanceof Set) {
        var items = [];
        value.forEach(function (v) {
          if (items.length < MAX_KEYS) items.push(serialize(v, depth + 1, seen));
        });
        return { __t: 'set', size: value.size, items: items };
      }
      if (Array.isArray(value) || (typeof ArrayBuffer !== 'undefined' && ArrayBuffer.isView && ArrayBuffer.isView(value))) {
        var out = [];
        for (var j = 0; j < Math.min(value.length, MAX_KEYS); j++) out.push(serialize(value[j], depth + 1, seen));
        if (value.length > MAX_KEYS) out.push({ __t: 'more', v: '… ' + (value.length - MAX_KEYS) + ' more' });
        return out;
      }
      var obj = {};
      var keys = Object.keys(value);
      for (var k = 0; k < Math.min(keys.length, MAX_KEYS); k++) {
        var key = keys[k];
        // Accessors are not run, as a console does not run them until asked: a getter can have side
        // effects (a framework's dependency tracking, a lazy fetch).
        var descriptor = Object.getOwnPropertyDescriptor(value, key);
        if (descriptor && descriptor.get) {
          obj[key] = { __t: 'getter', v: '(...)' };
          continue;
        }
        obj[key] = serialize(descriptor ? descriptor.value : undefined, depth + 1, seen);
      }
      if (keys.length > MAX_KEYS) obj['…'] = { __t: 'more', v: (keys.length - MAX_KEYS) + ' more keys' };
      var ctor = value.constructor && value.constructor.name;
      if (ctor && ctor !== 'Object') obj.__class = ctor;
      return obj;
    } catch (e) {
      return { __t: 'error', v: 'unserializable' };
    }
  }

  function callerLocation() {
    try {
      var lines = String(new Error().stack || '').split('\n');
      for (var i = 0; i < lines.length; i++) {
        var line = lines[i];
        if (line.indexOf('andcode-devtools') >= 0 || line.indexOf('__andcodeDT') >= 0) continue;
        var match = line.match(/(https?:\/\/[^\s)]+|file:[^\s)]+):(\d+):(\d+)/);
        if (match && i > 2) return match[1] + ':' + match[2];
      }
    } catch (e) {}
    return '';
  }

  // ---- Console --------------------------------------------------------------------------------

  var counters = {};
  var timers = {};
  ['log', 'info', 'warn', 'error', 'debug', 'trace', 'table', 'dir', 'assert', 'count', 'time', 'timeEnd', 'group', 'groupCollapsed', 'groupEnd', 'clear'].forEach(function (method) {
    var original = console[method];
    if (typeof original !== 'function') return;
    console[method] = function () {
      var args = Array.prototype.slice.call(arguments);
      if (!bridge) return original.apply(console, args);
      try {
        var level = method;
        var payload = args;
        if (method === 'assert') {
          if (args[0]) return original.apply(console, args);
          level = 'error';
          payload = ['Assertion failed:'].concat(args.slice(1));
        } else if (method === 'count') {
          var label = args.length ? String(args[0]) : 'default';
          counters[label] = (counters[label] || 0) + 1;
          level = 'info';
          payload = [label + ': ' + counters[label]];
        } else if (method === 'time') {
          timers[args.length ? String(args[0]) : 'default'] = performance.now();
          return original.apply(console, args);
        } else if (method === 'timeEnd') {
          var name = args.length ? String(args[0]) : 'default';
          var started = timers[name];
          delete timers[name];
          level = 'info';
          payload = [name + ': ' + (started === undefined ? 'no such timer' : (performance.now() - started).toFixed(2) + ' ms')];
        } else if (method === 'trace') {
          level = 'debug';
          payload = args.concat([{ __t: 'stack', v: clip(String(new Error().stack || ''), MAX_STRING) }]);
        } else if (method === 'dir' || method === 'table') {
          level = 'log';
        } else if (method === 'group' || method === 'groupCollapsed') {
          level = 'group';
        } else if (method === 'groupEnd') {
          level = 'groupEnd';
          payload = [];
        } else if (method === 'clear') {
          level = 'clear';
          payload = [];
        }
        post({ t: 'console', level: level, args: payload.map(function (a) { return serialize(a); }), source: callerLocation(), ts: now() });
      } catch (e) {}
      return original.apply(console, args);
    };
  });

  window.addEventListener('pagehide', flushAll);

  window.addEventListener('error', function (event) {
    try {
      var target = event.target;
      if (target && target !== window && (target.src || target.href)) {
        // A script, image or stylesheet that failed to load.
        post({ t: 'error', kind: 'resource', message: 'Failed to load ' + (target.tagName || '').toLowerCase() + ': ' + (target.src || target.href), source: target.src || target.href, ts: now() });
        return;
      }
      post({
        t: 'error',
        kind: 'uncaught',
        message: String(event.message || (event.error && event.error.message) || 'Error'),
        source: event.filename ? event.filename + ':' + event.lineno + ':' + event.colno : '',
        stack: event.error && event.error.stack ? clip(event.error.stack, MAX_STRING) : '',
        ts: now(),
      });
    } catch (e) {}
  }, true);

  window.addEventListener('unhandledrejection', function (event) {
    try {
      var reason = event.reason;
      post({
        t: 'error',
        kind: 'promise',
        message: 'Uncaught (in promise) ' + (reason instanceof Error ? reason.name + ': ' + reason.message : JSON.stringify(serialize(reason))),
        stack: reason && reason.stack ? clip(reason.stack, MAX_STRING) : '',
        ts: now(),
      });
    } catch (e) {}
  });

  // ---- Network --------------------------------------------------------------------------------

  var requestSeq = 0;

  function nextRequestId() {
    requestSeq += 1;
    return (isTop ? 't' : 'f') + requestSeq + '-' + now();
  }

  function headersToList(headers) {
    var list = [];
    try {
      if (!headers) return list;
      if (typeof headers.forEach === 'function' && !Array.isArray(headers)) {
        headers.forEach(function (value, key) { list.push([key, value]); });
      } else if (Array.isArray(headers)) {
        headers.forEach(function (pair) { list.push([String(pair[0]), String(pair[1])]); });
      } else {
        Object.keys(headers).forEach(function (key) { list.push([key, String(headers[key])]); });
      }
    } catch (e) {}
    return list;
  }

  function bodyPreview(body) {
    try {
      if (body === undefined || body === null) return null;
      if (typeof body === 'string') return clip(body, MAX_BODY);
      if (typeof URLSearchParams !== 'undefined' && body instanceof URLSearchParams) return clip(body.toString(), MAX_BODY);
      if (typeof FormData !== 'undefined' && body instanceof FormData) {
        var parts = [];
        body.forEach(function (value, key) { parts.push(key + '=' + (typeof value === 'string' ? value : '[file]')); });
        return clip(parts.join('&'), MAX_BODY);
      }
      if (typeof Blob !== 'undefined' && body instanceof Blob) return '[Blob ' + body.size + ' bytes]';
      if (typeof ArrayBuffer !== 'undefined' && (body instanceof ArrayBuffer || ArrayBuffer.isView(body))) return '[binary ' + body.byteLength + ' bytes]';
      return clip(String(body), MAX_BODY);
    } catch (e) {
      return null;
    }
  }

  // Server-sent events and line-delimited streams never end; reading them would never finish.
  function isStream(mime) {
    return /event-stream|ndjson|x-ndjson|stream\+json/i.test(mime || '');
  }

  /*
   * Reads at most MAX_BODY bytes of a cloned response, then cancels the clone, so DevTools never
   * makes the page download more than a preview for it, and a body the page abandons is abandoned.
   */
  function readPreview(clone, done) {
    var reader;
    try {
      reader = clone.body.getReader();
    } catch (e) {
      done('', null, false);
      return;
    }
    var decoder = new TextDecoder();
    var text = '';
    var bytes = 0;
    function step() {
      reader.read().then(function (chunk) {
        if (chunk.done) {
          text += decoder.decode();
          done(text, bytes, true);
          return;
        }
        bytes += chunk.value.byteLength;
        text += decoder.decode(chunk.value, { stream: true });
        if (bytes >= MAX_BODY) {
          try { reader.cancel(); } catch (e) {}
          done(clip(text, MAX_BODY), bytes, false);
          return;
        }
        step();
      }, function () {
        done(text, bytes, false);
      });
    }
    step();
  }

  function isTextual(mime) {
    return !mime || /json|text|xml|javascript|html|css|svg|x-www-form-urlencoded|graphql/i.test(mime);
  }

  if (typeof window.fetch === 'function') {
    var originalFetch = window.fetch;
    window.fetch = function (input, init) {
      var id = nextRequestId();
      var started = performance.now();
      try {
        var request = input instanceof Request ? input : null;
        var url = request ? request.url : String(input);
        try { url = new URL(url, location.href).href; } catch (e) {}
        post({
          t: 'net', phase: 'start', id: id, kind: 'fetch',
          method: String((init && init.method) || (request && request.method) || 'GET').toUpperCase(),
          url: url,
          reqHeaders: headersToList((init && init.headers) || (request && request.headers)),
          reqBody: bodyPreview(init && init.body),
          ts: now(),
        });
      } catch (e) {}
      return originalFetch.apply(this, arguments).then(function (response) {
        try {
          var mime = response.headers.get('content-type') || '';
          var base = {
            t: 'net', phase: 'end', id: id, status: response.status, statusText: response.statusText,
            resHeaders: headersToList(response.headers), mime: mime, type: response.type,
            redirected: response.redirected, finalUrl: response.url,
          };
          if (isTextual(mime) && !isStream(mime) && response.type !== 'opaque' && response.body && response.body.getReader) {
            readPreview(response.clone(), function (text, size, complete) {
              base.size = size;
              base.resBody = complete ? text : text + '\u2026 (body truncated)';
              base.duration = performance.now() - started;
              post(base);
            });
          } else {
            base.duration = performance.now() - started;
            base.size = Number(response.headers.get('content-length')) || null;
            post(base);
          }
        } catch (e) {}
        return response;
      }, function (error) {
        try {
          post({ t: 'net', phase: 'error', id: id, error: String(error && error.message || error), duration: performance.now() - started });
        } catch (e) {}
        throw error;
      });
    };
  }

  if (typeof XMLHttpRequest !== 'undefined') {
    var xhrOpen = XMLHttpRequest.prototype.open;
    var xhrSend = XMLHttpRequest.prototype.send;
    var xhrSetHeader = XMLHttpRequest.prototype.setRequestHeader;
    XMLHttpRequest.prototype.open = function (method, url) {
      try {
        var resolved = String(url);
        try { resolved = new URL(resolved, location.href).href; } catch (e) {}
        this.__andcode = { id: nextRequestId(), method: String(method || 'GET').toUpperCase(), url: resolved, headers: [] };
      } catch (e) {}
      return xhrOpen.apply(this, arguments);
    };
    XMLHttpRequest.prototype.setRequestHeader = function (name, value) {
      try {
        if (this.__andcode) this.__andcode.headers.push([String(name), String(value)]);
      } catch (e) {}
      return xhrSetHeader.apply(this, arguments);
    };
    XMLHttpRequest.prototype.send = function (body) {
      var xhr = this;
      var meta = xhr.__andcode;
      if (meta) {
        var started = performance.now();
        try {
          post({ t: 'net', phase: 'start', id: meta.id, kind: 'xhr', method: meta.method, url: meta.url, reqHeaders: meta.headers, reqBody: bodyPreview(body), ts: now() });
          xhr.addEventListener('loadend', function () {
            try {
              var raw = xhr.getAllResponseHeaders() || '';
              var headers = raw.trim().split(/[\r\n]+/).filter(Boolean).map(function (line) {
                var at = line.indexOf(':');
                return [line.slice(0, at).trim(), line.slice(at + 1).trim()];
              });
              var mime = xhr.getResponseHeader('content-type') || '';
              var text = null;
              if (xhr.responseType === '' || xhr.responseType === 'text') text = xhr.responseText;
              else if (xhr.responseType === 'json') text = JSON.stringify(xhr.response);
              if (xhr.status === 0) {
                post({ t: 'net', phase: 'error', id: meta.id, error: 'Request failed (network error, CORS or aborted)', duration: performance.now() - started });
                return;
              }
              post({
                t: 'net', phase: 'end', id: meta.id, status: xhr.status, statusText: xhr.statusText, resHeaders: headers,
                mime: mime, resBody: text === null ? null : clip(text, MAX_BODY), size: text === null ? null : text.length,
                duration: performance.now() - started, finalUrl: xhr.responseURL,
              });
            } catch (e) {}
          });
        } catch (e) {}
      }
      return xhrSend.apply(this, arguments);
    };
  }

  if (typeof WebSocket !== 'undefined' && typeof Proxy !== 'undefined' && typeof Reflect !== 'undefined') {
    // A Proxy, not a replacement function: `new`, subclasses (`class X extends WebSocket`),
    // `instanceof`, `constructor` and the static constants all behave exactly as before.
    var socketIds = typeof WeakMap !== 'undefined' ? new WeakMap() : null;
    window.WebSocket = new Proxy(WebSocket, {
      construct: function (target, args, newTarget) {
        var socket = Reflect.construct(target, args, newTarget);
        try {
          var id = nextRequestId();
          if (socketIds) socketIds.set(socket, id);
          var resolved = String(args[0]);
          try { resolved = new URL(resolved, location.href).href; } catch (e) {}
          post({ t: 'net', phase: 'start', id: id, kind: 'websocket', method: 'WS', url: resolved, reqHeaders: [], ts: now() });
          var opened = performance.now();
          socket.addEventListener('open', function () { post({ t: 'net', phase: 'end', id: id, status: 101, statusText: 'Switching Protocols', duration: performance.now() - opened, mime: 'websocket' }); });
          socket.addEventListener('message', function (event) { post({ t: 'ws', id: id, dir: 'in', data: typeof event.data === 'string' ? clip(event.data, MAX_STRING) : '[binary]', ts: now() }); });
          socket.addEventListener('close', function (event) { post({ t: 'ws', id: id, dir: 'close', data: 'closed ' + event.code + (event.reason ? ' ' + event.reason : ''), ts: now() }); });
        } catch (e) {}
        return socket;
      },
    });
    // Outgoing frames are seen on the prototype, so a page that patches send later still works.
    var nativeSend = WebSocket.prototype.send;
    WebSocket.prototype.send = function (data) {
      try {
        var id = socketIds && socketIds.get(this);
        if (id) post({ t: 'ws', id: id, dir: 'out', data: typeof data === 'string' ? clip(data, MAX_STRING) : '[binary]', ts: now() });
      } catch (e) {}
      return nativeSend.apply(this, arguments);
    };
  }

  // Everything else the page loads (scripts, styles, images, fonts, the document itself) is reported
  // from the browser's own resource timings, which see what fetch/XHR hooks cannot.
  function resourceEntry(entry) {
    return {
      url: entry.name,
      initiator: entry.initiatorType || 'other',
      duration: entry.duration,
      start: (performance.timeOrigin || 0) + entry.startTime,
      transfer: entry.transferSize || 0,
      decoded: entry.decodedBodySize || 0,
      // 0 means unknown (no Timing-Allow-Origin, or a browser too old to say), not "failed".
      status: entry.responseStatus || 0,
    };
  }
  try {
    if (typeof PerformanceObserver !== 'undefined') {
      new PerformanceObserver(function (list) {
        var entries = list.getEntries().filter(function (e) {
          return e.initiatorType !== 'fetch' && e.initiatorType !== 'xmlhttprequest';
        }).map(resourceEntry);
        if (entries.length) post({ t: 'resources', entries: entries });
      }).observe({ type: 'resource', buffered: true });
    }
  } catch (e) {}

  // ---- Inspection -----------------------------------------------------------------------------

  var nodeIds = typeof WeakMap !== 'undefined' ? new WeakMap() : null;
  var nodesById = {};
  var nodeSeq = 0;

  function idOf(node) {
    if (!nodeIds) return 0;
    var id = nodeIds.get(node);
    if (!id) {
      nodeSeq += 1;
      id = nodeSeq;
      nodeIds.set(node, id);
    }
    nodesById[id] = node;
    return id;
  }

  function nodeById(id) {
    var node = nodesById[id];
    return node && node.isConnected !== false ? node : null;
  }

  function meaningfulChildren(node) {
    var out = [];
    var children = node.childNodes || [];
    for (var i = 0; i < children.length; i++) {
      var child = children[i];
      if (child.nodeType === 1) out.push(child);
      else if (child.nodeType === 3 && child.textContent.trim()) out.push(child);
      else if (child.nodeType === 8) out.push(child);
    }
    return out;
  }

  function summarize(node) {
    var base = { id: idOf(node), type: node.nodeType };
    if (node.nodeType === 1) {
      base.tag = node.tagName.toLowerCase();
      base.attrs = [];
      for (var i = 0; i < node.attributes.length && i < 30; i++) {
        base.attrs.push([node.attributes[i].name, clip(node.attributes[i].value, 300)]);
      }
      var kids = meaningfulChildren(node);
      base.childCount = kids.length;
      if (kids.length === 1 && kids[0].nodeType === 3) base.text = clip(kids[0].textContent.trim(), 200);
    } else if (node.nodeType === 3) {
      base.text = clip(node.textContent.trim(), 500);
    } else if (node.nodeType === 8) {
      base.text = clip(node.textContent, 300);
    }
    return base;
  }

  var overlay = null;

  function showOverlay(node) {
    try {
      if (!isTop && !document.body) return;
      if (!overlay) {
        overlay = document.createElement('div');
        overlay.setAttribute('data-andcode-devtools', 'overlay');
        overlay.style.cssText = 'position:fixed;pointer-events:none;z-index:2147483647;background:rgba(111,168,220,.35);outline:1px solid rgba(66,133,244,.9);transition:all 60ms ease-out;';
        var label = document.createElement('div');
        label.style.cssText = 'position:absolute;left:0;top:-20px;font:11px/18px monospace;background:#1e1e1e;color:#fff;padding:0 6px;border-radius:3px;white-space:nowrap;';
        overlay.appendChild(label);
      }
      if (!node || node.nodeType !== 1) {
        if (overlay.parentNode) overlay.parentNode.removeChild(overlay);
        return;
      }
      var rect = node.getBoundingClientRect();
      overlay.style.left = rect.left + 'px';
      overlay.style.top = rect.top + 'px';
      overlay.style.width = rect.width + 'px';
      overlay.style.height = rect.height + 'px';
      overlay.firstChild.textContent = describeNode(node) + '  ' + Math.round(rect.width) + '×' + Math.round(rect.height);
      overlay.firstChild.style.top = rect.top < 22 ? (rect.height + 2) + 'px' : '-20px';
      (document.body || document.documentElement).appendChild(overlay);
    } catch (e) {}
  }

  var inspecting = false;

  function onInspectMove(event) {
    if (!inspecting) return;
    var point = event.touches ? event.touches[0] : event;
    var target = document.elementFromPoint(point.clientX, point.clientY);
    if (target && target !== overlay) showOverlay(target);
  }

  function onInspectPick(event) {
    if (!inspecting) return;
    event.preventDefault();
    event.stopPropagation();
    var point = event.changedTouches ? event.changedTouches[0] : event;
    var target = document.elementFromPoint(point.clientX, point.clientY);
    if (!target) return;
    inspecting = false;
    showOverlay(target);
    var path = [];
    var walk = target;
    while (walk && walk.nodeType === 1) {
      path.unshift(idOf(walk));
      walk = walk.parentNode;
    }
    post({ t: 'inspect', id: idOf(target), path: path });
  }

  document.addEventListener('touchstart', onInspectMove, true);
  document.addEventListener('touchmove', onInspectMove, true);
  document.addEventListener('click', onInspectPick, true);

  function boxOf(style, prefix, suffix) {
    suffix = suffix || '';
    return ['top', 'right', 'bottom', 'left'].map(function (side) {
      return parseFloat(style.getPropertyValue(prefix + '-' + side + suffix)) || 0;
    });
  }

  function storageList(kind) {
    var list = [];
    try {
      var store = kind === 'session' ? window.sessionStorage : window.localStorage;
      for (var i = 0; i < store.length; i++) {
        var key = store.key(i);
        list.push([key, clip(store.getItem(key), MAX_BODY)]);
      }
    } catch (e) {}
    return list;
  }

  // ---- API for the app --------------------------------------------------------------------------

  var evalSeq = 0;

  window.__andcodeDT = {
    /** Evaluates [code] in the page's global scope, as the console prompt does. */
    evaluate: function (code) {
      evalSeq += 1;
      var token = evalSeq;
      try {
        var result = (0, eval)(String(code));
        if (result && typeof result.then === 'function') {
          result.then(function (value) {
            post({ t: 'evalAsync', token: token, ok: true, value: serialize(value) });
          }, function (error) {
            post({ t: 'evalAsync', token: token, ok: false, value: serialize(error) });
          });
          return JSON.stringify({ ok: true, pending: true, token: token, value: { __t: 'promise', v: 'Promise {<pending>}' } });
        }
        return JSON.stringify({ ok: true, value: serialize(result) });
      } catch (error) {
        return JSON.stringify({ ok: false, value: serialize(error) });
      }
    },

    /** The document's root, its <head> and <body> with their direct children. */
    root: function () {
      var html = document.documentElement;
      if (!html) return JSON.stringify(null);
      var root = summarize(html);
      root.children = meaningfulChildren(html).map(summarize);
      return JSON.stringify({ doctype: document.doctype ? '<!DOCTYPE ' + document.doctype.name + '>' : null, root: root });
    },

    children: function (id) {
      var node = nodeById(id);
      return JSON.stringify(node ? meaningfulChildren(node).map(summarize) : []);
    },

    /** Everything the Elements detail pane shows for one element. */
    details: function (id) {
      var node = nodeById(id);
      if (!node || node.nodeType !== 1) return JSON.stringify(null);
      var style = getComputedStyle(node);
      var computed = [];
      for (var i = 0; i < style.length; i++) {
        var name = style[i];
        computed.push([name, style.getPropertyValue(name)]);
      }
      var rect = node.getBoundingClientRect();
      var rules = [];
      try {
        for (var s = 0; s < document.styleSheets.length; s++) {
          var sheet = document.styleSheets[s];
          var cssRules;
          try { cssRules = sheet.cssRules; } catch (e) { continue; }
          for (var r = 0; r < cssRules.length && rules.length < 40; r++) {
            var rule = cssRules[r];
            if (rule.selectorText && node.matches && node.matches(rule.selectorText)) {
              rules.push({ selector: rule.selectorText, css: clip(rule.style.cssText, 2000), source: sheet.href || 'inline <style>' });
            }
          }
        }
      } catch (e) {}
      return JSON.stringify({
        id: id,
        tag: node.tagName.toLowerCase(),
        attrs: summarize(node).attrs,
        inline: node.getAttribute('style') || '',
        rules: rules,
        computed: computed,
        box: {
          x: rect.left, y: rect.top, width: rect.width, height: rect.height,
          margin: boxOf(style, 'margin'), padding: boxOf(style, 'padding'), border: boxOf(style, 'border', '-width'),
        },
        outerHTML: clip(node.outerHTML, MAX_BODY),
        text: clip(node.innerText || node.textContent || '', MAX_STRING),
      });
    },

    highlight: function (id) {
      showOverlay(id ? nodeById(id) : null);
      return 'true';
    },

    scrollIntoView: function (id) {
      var node = nodeById(id);
      if (node && node.scrollIntoView) node.scrollIntoView({ block: 'center', behavior: 'smooth' });
      showOverlay(node);
      return 'true';
    },

    setInspecting: function (on) {
      inspecting = !!on;
      if (!inspecting) showOverlay(null);
      return 'true';
    },

    setAttribute: function (id, name, value) {
      var node = nodeById(id);
      if (!node || node.nodeType !== 1) return 'false';
      if (value === null) node.removeAttribute(name);
      else node.setAttribute(name, value);
      return 'true';
    },

    setText: function (id, text) {
      var node = nodeById(id);
      if (!node) return 'false';
      node.textContent = text;
      return 'true';
    },

    remove: function (id) {
      var node = nodeById(id);
      if (!node || !node.parentNode) return 'false';
      node.parentNode.removeChild(node);
      showOverlay(null);
      return 'true';
    },

    storage: function () {
      var cookies = [];
      try {
        String(document.cookie || '').split(/;\s*/).filter(Boolean).forEach(function (pair) {
          var at = pair.indexOf('=');
          cookies.push(at < 0 ? [pair, ''] : [pair.slice(0, at), pair.slice(at + 1)]);
        });
      } catch (e) {}
      return JSON.stringify({ origin: location.origin, local: storageList('local'), session: storageList('session'), cookies: cookies });
    },

    storageSet: function (kind, key, value) {
      try {
        if (kind === 'cookie') document.cookie = key + '=' + value + '; path=/';
        else (kind === 'session' ? sessionStorage : localStorage).setItem(key, value);
        return 'true';
      } catch (e) {
        return 'false';
      }
    },

    storageRemove: function (kind, key) {
      try {
        if (kind === 'cookie') document.cookie = key + '=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
        else (kind === 'session' ? sessionStorage : localStorage).removeItem(key);
        return 'true';
      } catch (e) {
        return 'false';
      }
    },

    storageClear: function (kind) {
      try {
        if (kind === 'cookie') {
          String(document.cookie || '').split(/;\s*/).filter(Boolean).forEach(function (pair) {
            document.cookie = pair.split('=')[0] + '=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
          });
        } else {
          (kind === 'session' ? sessionStorage : localStorage).clear();
        }
        return 'true';
      } catch (e) {
        return 'false';
      }
    },

    /** Load timings, memory and the page's environment, for the Performance tab. */
    performance: function () {
      var nav = null;
      try {
        var entry = performance.getEntriesByType('navigation')[0];
        if (entry) {
          nav = {
            type: entry.type,
            redirect: entry.redirectEnd - entry.redirectStart,
            dns: entry.domainLookupEnd - entry.domainLookupStart,
            connect: entry.connectEnd - entry.connectStart,
            ttfb: entry.responseStart - entry.requestStart,
            download: entry.responseEnd - entry.responseStart,
            domInteractive: entry.domInteractive,
            domContentLoaded: entry.domContentLoadedEventEnd,
            load: entry.loadEventEnd,
            transfer: entry.transferSize || 0,
            decoded: entry.decodedBodySize || 0,
          };
        }
      } catch (e) {}
      var paints = {};
      try {
        performance.getEntriesByType('paint').forEach(function (p) { paints[p.name] = p.startTime; });
      } catch (e) {}
      var memory = null;
      try {
        if (performance.memory) memory = { used: performance.memory.usedJSHeapSize, total: performance.memory.totalJSHeapSize, limit: performance.memory.jsHeapSizeLimit };
      } catch (e) {}
      var resources = [];
      try {
        resources = performance.getEntriesByType('resource').map(resourceEntry);
      } catch (e) {}
      return JSON.stringify({
        url: location.href,
        title: document.title,
        nav: nav,
        paints: paints,
        memory: memory,
        nodes: document.getElementsByTagName('*').length,
        resources: resources,
        viewport: { width: window.innerWidth, height: window.innerHeight, dpr: window.devicePixelRatio },
        userAgent: navigator.userAgent,
        readyState: document.readyState,
      });
    },

    /**
     * Reads [url] as the page would (its cookies, credentials and CORS rules) and posts the text back
     * under [token]. Uses the unhooked fetch, so DevTools' own reads stay out of the Network log.
     */
    fetchText: function (url, token) {
      var get = typeof originalFetch === 'function' ? originalFetch : window.fetch;
      try {
        get.call(window, url).then(function (response) {
          return response.text();
        }).then(function (text) {
          post({ t: 'text', token: token, ok: true, text: clip(text, 2 * 1024 * 1024) });
        }, function (error) {
          post({ t: 'text', token: token, ok: false, text: String((error && error.message) || error) });
        });
      } catch (error) {
        post({ t: 'text', token: token, ok: false, text: String(error) });
      }
      return 'true';
    },

    /** Every script and stylesheet the page has, external or inline. */
    sources: function () {
      var list = [];
      try {
        var scripts = document.scripts;
        for (var i = 0; i < scripts.length; i++) {
          var s = scripts[i];
          list.push(s.src ? { kind: 'script', url: s.src } : { kind: 'script', url: '', inline: clip(s.textContent, MAX_BODY) });
        }
        var styles = document.querySelectorAll('link[rel~="stylesheet"], style');
        for (var j = 0; j < styles.length; j++) {
          var st = styles[j];
          list.push(st.href ? { kind: 'style', url: st.href } : { kind: 'style', url: '', inline: clip(st.textContent, MAX_BODY) });
        }
      } catch (e) {}
      return JSON.stringify(list);
    },
  };

  // First message from every new document, before anything it logs: the app starts a fresh log here
  // (unless the user preserves it), so nothing the page says early is cleared after the fact.
  post({ t: 'ready', url: String(location.href), title: document.title || '', ts: now() });
})();
