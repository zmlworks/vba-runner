/*
 * vba.js - VBA-style helper runtime for the script runner (runs inside a sandboxed WebView).
 * Copyright (c) 2026 zmlworks - MIT License
 *
 * The host object __host is a Java @JavascriptInterface (or a mock in tests/node).
 * All calls are synchronous; MsgBox / InputBox block the script via window.prompt(),
 * which the app answers with a native dialog (WebChromeClient.onJsPrompt).
 */
(function (g) {
  "use strict";
  var H = g.__host;
  var MAXR = H.rows(), MAXC = H.cols();

  // ---- value conversion (cells store strings; reading gives Variant-like values) ----
  var NUM_RE = /^[-+]?(\d+\.?\d*|\.\d+)([eE][-+]?\d+)?$/;
  function toVal(s) {
    if (s === null || s === undefined || s === "") return "";
    if (NUM_RE.test(s)) return Number(s);
    if (s === "TRUE") return true;
    if (s === "FALSE") return false;
    return String(s);
  }
  function toStr(v) {
    if (v === null || v === undefined) return "";
    if (v === true) return "TRUE";
    if (v === false) return "FALSE";
    if (v instanceof Date) return Format(v, "yyyy/mm/dd hh:nn:ss");
    if (typeof v === "number" && !isFinite(v)) return String(v);
    return String(v);
  }

  // ---- A1 addressing ----
  function colToNum(s) {
    var n = 0;
    for (var i = 0; i < s.length; i++) n = n * 26 + (s.charCodeAt(i) - 64);
    return n;
  }
  function numToCol(n) {
    var s = "";
    while (n > 0) { var m = (n - 1) % 26; s = String.fromCharCode(65 + m) + s; n = (n - 1 - m) / 26; }
    return s;
  }
  function parseA1(a) {
    var m = /^\$?([A-Za-z]{1,3})\$?(\d+)$/.exec(String(a).trim());
    if (!m) throw new Error("不正なセル参照 / bad cell reference: " + a);
    return { r: Number(m[2]), c: colToNum(m[1].toUpperCase()) };
  }
  function check(r, c) {
    if (!(r >= 1 && r <= MAXR && c >= 1 && c <= MAXC) || r !== Math.floor(r) || c !== Math.floor(c))
      throw new RangeError("範囲外のセル / cell out of range: (" + r + "," + c + ")");
  }

  // ---- Range object (a single cell is a 1x1 range) ----
  function RangeObj(r1, c1, r2, c2) {
    if (r2 === undefined) { r2 = r1; c2 = c1; }
    var t;
    if (r1 > r2) { t = r1; r1 = r2; r2 = t; }
    if (c1 > c2) { t = c1; c1 = c2; c2 = t; }
    check(r1, c1); check(r2, c2);
    this.r1 = r1; this.c1 = c1; this.r2 = r2; this.c2 = c2;
  }
  var RP = RangeObj.prototype;
  function isSingle(rg) { return rg.r1 === rg.r2 && rg.c1 === rg.c2; }
  Object.defineProperty(RP, "Row", { get: function () { return this.r1; } });
  Object.defineProperty(RP, "Column", { get: function () { return this.c1; } });
  Object.defineProperty(RP, "Count", { get: function () { return (this.r2 - this.r1 + 1) * (this.c2 - this.c1 + 1); } });
  Object.defineProperty(RP, "RowsCount", { get: function () { return this.r2 - this.r1 + 1; } });
  Object.defineProperty(RP, "ColumnsCount", { get: function () { return this.c2 - this.c1 + 1; } });
  Object.defineProperty(RP, "Address", {
    get: function () {
      var a = "$" + numToCol(this.c1) + "$" + this.r1;
      return isSingle(this) ? a : a + ":$" + numToCol(this.c2) + "$" + this.r2;
    }
  });
  Object.defineProperty(RP, "Value", {
    get: function () {
      if (isSingle(this)) return toVal(H.get(this.r1, this.c1));
      var raw = JSON.parse(H.getBlock(this.r1, this.c1, this.r2, this.c2));
      return raw.map(function (row) { return row.map(toVal); });
    },
    set: function (v) {
      var rows = this.r2 - this.r1 + 1, cols = this.c2 - this.c1 + 1, out = [], i, j;
      if (isSingle(this) && !Array.isArray(v)) { H.set(this.r1, this.c1, toStr(v)); return; }
      for (i = 0; i < rows; i++) {
        out.push([]);
        for (j = 0; j < cols; j++) {
          var x = Array.isArray(v) ? (Array.isArray(v[i]) ? v[i][j] : (rows === 1 ? v[j] : v[i])) : v;
          out[i].push(toStr(x));
        }
      }
      H.setBlock(this.r1, this.c1, JSON.stringify(out));
    }
  });
  Object.defineProperty(RP, "Text", { get: function () { return isSingle(this) ? H.get(this.r1, this.c1) : ""; } });
  RP.Cells = function (r, c) { return new RangeObj(this.r1 + r - 1, this.c1 + c - 1); };
  RP.Offset = function (dr, dc) {
    return new RangeObj(this.r1 + (dr || 0), this.c1 + (dc || 0), this.r2 + (dr || 0), this.c2 + (dc || 0));
  };
  RP.ClearContents = function () { this.Value = ""; };
  RP.Clear = RP.ClearContents;
  /** forEach(function(cell, rowIndex, colIndex){...}) — cells in row-major order */
  RP.forEach = function (fn) {
    for (var r = this.r1; r <= this.r2; r++)
      for (var c = this.c1; c <= this.c2; c++) fn(new RangeObj(r, c), r - this.r1 + 1, c - this.c1 + 1);
  };
  RP.values = function () {
    var a = [];
    this.forEach(function (cell) { a.push(cell.Value); });
    return a;
  };
  RP.toString = function () { return toStr(isSingle(this) ? this.Value : this.Address); };

  function Cells(r, c) { return new RangeObj(r, c); }
  function Range(a, b) {
    if (a instanceof RangeObj && b instanceof RangeObj) return new RangeObj(a.r1, a.c1, b.r2, b.c2);
    var parts = String(a).split(":");
    var p = parseA1(parts[0]), q = parts.length > 1 ? parseA1(parts[1]) : p;
    return new RangeObj(p.r, p.c, q.r, q.c);
  }

  // ---- worksheet helpers ----
  function numbers(args) {
    var out = [];
    Array.prototype.forEach.call(args, function (a) {
      var vals = a instanceof RangeObj ? a.values() : (Array.isArray(a) ? [].concat.apply([], a) : [a]);
      vals.forEach(function (v) { if (typeof v === "number") out.push(v); });
    });
    return out;
  }
  var WorksheetFunction = {
    Sum: function () { return numbers(arguments).reduce(function (s, x) { return s + x; }, 0); },
    Average: function () {
      var n = numbers(arguments);
      if (!n.length) throw new Error("#DIV/0!");
      return WorksheetFunction.Sum(n) / n.length;
    },
    Max: function () { var n = numbers(arguments); return n.length ? Math.max.apply(null, n) : 0; },
    Min: function () { var n = numbers(arguments); return n.length ? Math.min.apply(null, n) : 0; },
    Count: function () { return numbers(arguments).length; },
    CountA: function (rg) { return rg.values().filter(function (v) { return v !== ""; }).length; }
  };
  var Sheet = {
    Clear: function () { H.clear(); },
    get Rows() { return MAXR; },
    get Columns() { return MAXC; },
    /** last non-empty row in a column (like Cells(Rows.Count, c).End(xlUp).Row); 0 if empty */
    LastRow: function (c) {
      if (typeof c === "string") c = colToNum(c.toUpperCase());
      return H.lastRow(c || 1);
    }
  };

  // ---- dialogs (blocking, answered natively) ----
  function dialog(obj) { return g.prompt("@@VBA@@" + JSON.stringify(obj), ""); }
  var vb = {
    vbOKOnly: 0, vbOKCancel: 1, vbAbortRetryIgnore: 2, vbYesNoCancel: 3, vbYesNo: 4, vbRetryCancel: 5,
    vbCritical: 16, vbQuestion: 32, vbExclamation: 48, vbInformation: 64,
    vbOK: 1, vbCancel: 2, vbAbort: 3, vbRetry: 4, vbIgnore: 5, vbYes: 6, vbNo: 7,
    vbCrLf: "\n", vbCr: "\n", vbLf: "\n", vbNewLine: "\n", vbTab: "\t", vbNullString: ""
  };
  function MsgBox(prompt, buttons, title) {
    var r = dialog({ t: "msg", m: toStr(prompt), b: (buttons || 0) & 7, title: title === undefined ? "" : toStr(title) });
    return r === null ? vb.vbCancel : Number(r);
  }
  function InputBox(prompt, title, def) {
    var r = dialog({ t: "input", m: toStr(prompt), title: title === undefined ? "" : toStr(title), d: def === undefined ? "" : toStr(def) });
    return r === null ? "" : r; // VBA returns "" on cancel
  }
  var Debug = {
    Print: function () { H.print(Array.prototype.map.call(arguments, toStr).join(" ")); }
  };

  // ---- UserForm-ish panel ----
  var handlers = {};
  function Control(id) { this.id = id; }
  Object.defineProperty(Control.prototype, "Value", {
    get: function () { return H.formGet(this.id); },
    set: function (v) { H.formSet(this.id, toStr(v)); }
  });
  Object.defineProperty(Control.prototype, "Caption", Object.getOwnPropertyDescriptor(Control.prototype, "Value"));
  Object.defineProperty(Control.prototype, "Text", Object.getOwnPropertyDescriptor(Control.prototype, "Value"));
  var named = {};
  var UserForm = {
    Clear: function () { H.formClear(); handlers = {}; named = {}; },
    Show: function () { H.formShow(); },
    AddLabel: function (text) { return new Control(H.formAddLabel(toStr(text))); },
    AddTextBox: function (name, value) {
      var c = new Control(H.formAddTextBox(toStr(name || ""), toStr(value)));
      if (name) named[name] = c;
      return c;
    },
    AddButton: function (caption, onClick) {
      var id = H.formAddButton(toStr(caption));
      if (typeof onClick === "function") handlers[id] = onClick;
      return new Control(id);
    },
    Controls: function (name) { return named[name]; }
  };
  g.__fire = function (id) {
    var f = handlers[id];
    if (!f) return;
    try { f(); } catch (e) { H.error(fmtErr(e)); }
  };

  // ---- VBA-ish functions ----
  function bankers(x) { // VBA CInt/Round use banker's rounding
    var f = Math.floor(x), d = x - f;
    if (Math.abs(d - 0.5) < 1e-12) return f % 2 === 0 ? f : f + 1;
    return Math.round(x);
  }
  function pad(n, w) { n = String(n); while (n.length < w) n = "0" + n; return n; }
  function Format(v, fmt) {
    if (fmt === undefined) return toStr(v);
    if (v instanceof Date) {
      return fmt.replace(/yyyy|mm|dd|hh|nn|ss/g, function (k) {
        switch (k) {
          case "yyyy": return v.getFullYear();
          case "mm": return pad(v.getMonth() + 1, 2);
          case "dd": return pad(v.getDate(), 2);
          case "hh": return pad(v.getHours(), 2);
          case "nn": return pad(v.getMinutes(), 2);
          default: return pad(v.getSeconds(), 2);
        }
      });
    }
    var x = Number(v);
    if (isNaN(x)) return toStr(v);
    var m = /^([#,0]*)(?:\.(0+))?(%?)$/.exec(fmt);
    if (!m) return toStr(v);
    if (m[3]) x *= 100;
    var dec = m[2] ? m[2].length : 0;
    var s = Math.abs(x).toFixed(dec), neg = x < 0 && Number(s) !== 0;
    var ip = s.split(".")[0], fp = s.split(".")[1];
    var minInt = (m[1].replace(/[#,]/g, "").length);
    while (ip.length < minInt) ip = "0" + ip;
    if (minInt === 0 && ip === "0" && dec > 0) ip = "";
    if (m[1].indexOf(",") >= 0) ip = ip.replace(/\B(?=(\d{3})+(?!\d))/g, ",");
    return (neg ? "-" : "") + ip + (fp ? "." + fp : "") + m[3];
  }
  var fns = {
    Cells: Cells, Range: Range, Sheet: Sheet, ActiveSheet: Sheet, WorksheetFunction: WorksheetFunction,
    MsgBox: MsgBox, InputBox: InputBox, Debug: Debug, UserForm: UserForm, Format: Format,
    Len: function (s) { return toStr(s).length; },
    Left: function (s, n) { return toStr(s).substring(0, n); },
    Right: function (s, n) { s = toStr(s); return s.substring(Math.max(0, s.length - n)); },
    Mid: function (s, start, n) { s = toStr(s); return n === undefined ? s.substring(start - 1) : s.substr(start - 1, n); },
    UCase: function (s) { return toStr(s).toUpperCase(); },
    LCase: function (s) { return toStr(s).toLowerCase(); },
    Trim: function (s) { return toStr(s).trim(); },
    InStr: function (a, b, c) { // InStr([start,] s, find) -> 1-based, 0 if not found
      if (c === undefined) return toStr(a).indexOf(toStr(b)) + 1;
      return toStr(b).indexOf(toStr(c), a - 1) + 1;
    },
    Replace: function (s, f, r) { return toStr(s).split(toStr(f)).join(toStr(r)); },
    Split: function (s, d) { return toStr(s).split(d === undefined ? " " : d); },
    Join: function (a, d) { return a.join(d === undefined ? " " : d); },
    StrReverse: function (s) { return toStr(s).split("").reverse().join(""); },
    Chr: function (n) { return String.fromCharCode(n); },
    Asc: function (s) { return toStr(s).charCodeAt(0); },
    CStr: toStr,
    CDbl: function (v) { var x = Number(v); if (isNaN(x)) throw new TypeError("型が一致しません / Type mismatch"); return x; },
    CInt: function (v) { var x = fns.CDbl(v); return bankers(x); },
    CLng: function (v) { return fns.CInt(v); },
    Val: function (s) { var x = parseFloat(toStr(s)); return isNaN(x) ? 0 : x; },
    IsNumeric: function (v) { return v !== "" && v !== null && !isNaN(Number(v)); },
    IsEmpty: function (v) { return v === "" || v === undefined || v === null; },
    Int: Math.floor, Fix: Math.trunc, Abs: Math.abs, Sqr: Math.sqrt, Rnd: Math.random,
    Round: function (x, d) { var p = Math.pow(10, d || 0); return bankers(x * p) / p; },
    Now: function () { return new Date(); },
    Timer: function () { var d = new Date(); return (d - new Date(d.getFullYear(), d.getMonth(), d.getDate())) / 1000; },
    ColumnLetter: numToCol, ColumnNumber: function (s) { return colToNum(String(s).toUpperCase()); }
  };
  Object.keys(vb).forEach(function (k) { g[k] = vb[k]; });
  Object.keys(fns).forEach(function (k) { g[k] = fns[k]; });

  // ---- running scripts ----
  function fmtErr(e) {
    var line = null, m = /macro\.js:(\d+)/.exec(String(e && e.stack));
    if (m) line = Number(m[1]);
    var msg = (e && e.name ? e.name + ": " : "") + (e && e.message !== undefined ? e.message : String(e));
    return (line ? "行 " + line + " / line " + line + ": " : "") + msg;
  }
  g.__fmtErr = fmtErr;
  /** Run user code at global scope (so top-level function declarations become globals);
   * afterwards call Main() if the script defines one. */
  g.__run = function (code) {
    H.begin();
    g.__inRun = true;
    try { g.Main = undefined; } catch (e) { /* a Main from an earlier run in the same page */ }
    var src = code + "\n//# sourceURL=macro.js";
    if (g.document) {
      // a <script> element reports syntax errors with line numbers via window.onerror
      g.__err = null;
      var s = g.document.createElement("script");
      s.text = src;
      g.document.head.appendChild(s);
      s.remove();
      if (g.__err) { g.__inRun = false; H.done(g.__err); return; }
    } else {
      try { (0, eval)(src); } catch (e) { g.__inRun = false; H.done(fmtErr(e)); return; }
    }
    try {
      if (typeof g.Main === "function") g.Main();
      g.__inRun = false;
      H.done("");
    } catch (e) { g.__inRun = false; H.done(fmtErr(e)); }
  };
  if (g.addEventListener) {
    g.addEventListener("error", function (ev) {
      var e = ev.error;
      var msg = (ev.lineno ? "行 " + ev.lineno + " / line " + ev.lineno + ": " : "") +
        (e && e.name ? e.name + ": " + e.message : ev.message).replace(/Failed to execute '\w+' on '\w+': /, "");
      if (g.__inRun) g.__err = msg; else H.error(msg);   // e.g. an exception inside setTimeout
      ev.preventDefault();
    });
  }
})(this);
