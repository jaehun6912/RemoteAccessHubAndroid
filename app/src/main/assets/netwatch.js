// RemoteAccessHub 안드로이드: 공유기 API(/cgi/service.cgi) 호출 관찰 스크립트.
// Windows 버전은 DevTools 프로토콜(Network.*)로 관찰하지만 안드로이드 WebView에는 그 기능이 없어,
// 페이지가 시작될 때 XMLHttpRequest와 fetch를 감싸 같은 정보(method 이름, 상태 코드, 결과 정상/오류 코드)만 기록한다.
// - 로그인 요청(session/login)의 params(자격 증명)는 읽지 않는다.
// - 응답 본문은 저장하지 않고 정상/오류 코드만 남긴다.
// - 기록은 이 페이지 안(window.__rahNet)에만 있고, 앱이 주기적으로 읽어 간다(페이지로 무엇을 보내지 않는다).
(function () {
  if (window.__rahNet) return;
  var net = { doc: Math.random().toString(36).slice(2) + Date.now().toString(36), ver: 0, seq: 0, calls: [] };
  try {
    Object.defineProperty(window, '__rahNet', { value: net, writable: false, configurable: false, enumerable: false });
  } catch (e) {
    window.__rahNet = net;
  }
  var BODY = { 'wol/signal': 1, 'session/login': 1, 'session/logout': 1, 'session/update': 1, 'session/info': 1 };
  var MAX = 300;

  function isApi(url) {
    try { return String(url || '').indexOf('service.cgi') >= 0; } catch (e) { return false; }
  }
  function absolute(url) {
    try { return new URL(String(url), location.href).href.split('?')[0]; } catch (e) { return String(url || '').split('?')[0]; }
  }
  function decode(buf) {
    try { return new TextDecoder().decode(buf instanceof ArrayBuffer ? new Uint8Array(buf) : buf); } catch (e) { return null; }
  }
  function bodyText(b) {
    try {
      if (b == null) return null;
      if (typeof b === 'string') return b;
      if (b instanceof ArrayBuffer || ArrayBuffer.isView(b)) return decode(b);
      if (typeof URLSearchParams !== 'undefined' && b instanceof URLSearchParams) return b.toString();
    } catch (e) {}
    return null;
  }
  function parseReq(b) {
    var s = bodyText(b);
    if (!s) return { method: '', params: null };
    try {
      var j = JSON.parse(s);
      var m = (j && typeof j.method === 'string') ? j.method : '';
      var p = null;
      // 로그인 호출의 params(아이디·비밀번호·보안문자)는 절대 기록하지 않는다.
      if (j && j.params !== undefined && m.indexOf('session/login') !== 0) {
        try { p = JSON.stringify(j.params); if (p && p.length > 300) p = p.slice(0, 300); } catch (e) {}
      }
      return { method: m, params: p };
    } catch (e) {
      return { method: '', params: null };
    }
  }
  function parseRes(t) {
    try {
      var j = JSON.parse(t);
      if (j && j.error && typeof j.error === 'object') {
        return {
          ok: false,
          code: typeof j.error.code === 'number' ? j.error.code : null,
          msg: typeof j.error.message === 'string' ? j.error.message.slice(0, 80) : null
        };
      }
      return { ok: !!(j && j.result !== undefined && j.result !== null), code: null, msg: null };
    } catch (e) {
      return { ok: false, code: null, msg: '응답 형식 불명' };
    }
  }
  function touch(c) { c.v = ++net.ver; }
  function newCall(url) {
    return { id: 0, t: 0, sent: false, method: '', params: null, path: absolute(url), status: null,
      done: false, failed: false, err: null, checked: false, ok: null, code: null, msg: null, v: 0 };
  }
  function push(c) {
    c.id = ++net.seq;
    net.calls.push(c);
    if (net.calls.length > MAX) net.calls.splice(0, net.calls.length - MAX);
    touch(c);
  }
  function setReq(c, body) {
    var r = parseReq(body);
    c.method = r.method;
    c.params = r.params;
  }
  // 결과 판정이 필요한 호출만 본문을 읽고, 읽은 뒤에는 정상/오류 코드만 남긴다.
  function setResult(c, text) {
    if (!BODY[c.method]) return;
    if (text != null) {
      var r = parseRes(text);
      c.ok = r.ok; c.code = r.code; c.msg = r.msg;
    }
    c.checked = true;
  }
  function fail(c, err) {
    if (c.done || c.failed) return;
    c.failed = true;
    c.err = err;
    if (BODY[c.method] && c.status !== null) c.checked = true;
    touch(c);
  }
  function abortText(e) { return (e && e.name === 'AbortError') ? 'net::ERR_ABORTED' : 'net::ERR_FAILED'; }

  // ---------- XMLHttpRequest ----------
  var XP = window.XMLHttpRequest && XMLHttpRequest.prototype;
  if (XP) {
    var open0 = XP.open, send0 = XP.send;
    function textOf(x) {
      try {
        var rt = x.responseType;
        if (rt === '' || rt === 'text') return x.responseText;
        if (rt === 'json') return x.response == null ? null : JSON.stringify(x.response);
        if (rt === 'arraybuffer') return x.response ? decode(x.response) : null;
      } catch (e) {}
      return null;
    }
    XP.open = function (method, url) {
      try {
        var x = this;
        x.__rah = null;
        if (isApi(url)) {
          var c = newCall(url);
          x.__rah = c;
          // 공유기 앱의 처리기보다 먼저 결과를 읽도록 open에서 등록한다.
          // (공유기 앱은 응답을 읽은 뒤 통신 객체를 닫는데, 그 뒤에는 상태 코드와 본문을 읽을 수 없다.)
          x.addEventListener('readystatechange', function () {
            if (!c.sent) return;
            try {
              if (x.readyState >= 2 && c.status === null && x.status) { c.status = x.status; touch(c); }
              if (x.readyState === 4 && x.status && !c.done && !c.failed) {
                c.status = x.status;
                c.done = true;
                if (BODY[c.method] && x.responseType === 'blob' && x.response && x.response.text) {
                  touch(c);
                  x.response.text().then(function (t) { setResult(c, t); touch(c); }, function () { c.checked = true; touch(c); });
                } else {
                  setResult(c, textOf(x));
                  touch(c);
                }
              }
            } catch (e) {}
          });
          x.addEventListener('error', function () { if (c.sent) fail(c, 'net::ERR_FAILED'); });
          x.addEventListener('abort', function () { if (c.sent) fail(c, 'net::ERR_ABORTED'); });
          x.addEventListener('timeout', function () { if (c.sent) fail(c, 'net::ERR_TIMED_OUT'); });
        }
      } catch (e) {}
      return open0.apply(this, arguments);
    };
    XP.send = function (body) {
      try {
        var c = this.__rah;
        if (c && !c.sent) {
          c.sent = true;
          c.t = Date.now();
          setReq(c, body);
          push(c);
        }
      } catch (e) {}
      return send0.apply(this, arguments);
    };
  }

  // ---------- fetch ----------
  var fetch0 = window.fetch;
  if (typeof fetch0 === 'function') {
    window.fetch = function (input, init) {
      var url = '';
      try { url = typeof input === 'string' ? input : (input && input.url) || String(input); } catch (e) {}
      if (!isApi(url)) return fetch0.apply(this, arguments);
      var c = newCall(url);
      c.sent = true;
      c.t = Date.now();
      try { setReq(c, init && init.body); } catch (e) {}
      push(c);
      var p = fetch0.apply(this, arguments);
      p.then(function (r) {
        c.status = r.status;
        touch(c);
        var copy = null;
        try { copy = r.clone(); } catch (e) {}
        if (!copy) { c.done = true; if (BODY[c.method]) c.checked = true; touch(c); return; }
        copy.text().then(function (t) {
          if (c.failed) return;
          c.done = true;
          setResult(c, t);
          touch(c);
        }, function (e) { fail(c, abortText(e)); });
      }, function (e) { fail(c, abortText(e)); });
      return p;
    };
  }
})();
