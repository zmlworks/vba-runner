// node test for app/src/main/assets/vba.js with a mock __host.  Run: node tools/test-vba.js
"use strict";
const fs = require("fs"), path = require("path"), vm = require("vm"), assert = require("assert");
const ROOT = path.join(__dirname, "..", "app", "src", "main", "assets");
const SRC = fs.readFileSync(path.join(ROOT, "vba.js"), "utf8");

function makeHost(answers) {
  const R = 100, C = 26, cells = {}, log = [], form = {};
  let nextId = 1, doneMsg;
  const host = {
    rows: () => R, cols: () => C,
    get: (r, c) => cells[r + "," + c] || "",
    set: (r, c, v) => { if (v === "") delete cells[r + "," + c]; else cells[r + "," + c] = v; },
    getBlock: (r1, c1, r2, c2) => { const o = []; for (let r = r1; r <= r2; r++) { const row = []; for (let c = c1; c <= c2; c++) row.push(host.get(r, c)); o.push(row); } return JSON.stringify(o); },
    setBlock: (r1, c1, json) => JSON.parse(json).forEach((row, i) => row.forEach((v, j) => host.set(r1 + i, c1 + j, v))),
    clear: () => { for (const k in cells) delete cells[k]; },
    lastRow: (c) => { for (let r = R; r >= 1; r--) if (host.get(r, c) !== "") return r; return 0; },
    print: (s) => log.push(s), error: (s) => log.push("ERR " + s),
    begin: () => { doneMsg = undefined; }, done: (m) => { doneMsg = m; },
    formClear: () => {}, formShow: () => {},
    formAddLabel: (t) => { const id = nextId++; form[id] = t; return id; },
    formAddTextBox: (n, v) => { const id = nextId++; form[id] = v; return id; },
    formAddButton: (t) => { const id = nextId++; form[id] = t; return id; },
    formGet: (id) => form[id], formSet: (id, v) => { form[id] = v; },
  };
  const ctx = { __host: host, JSON, Math, Date, console,
    prompt: (msg) => { assert(msg.startsWith("@@VBA@@")); const o = JSON.parse(msg.slice(7)); return answers.shift()(o); } };
  vm.createContext(ctx);
  vm.runInContext(SRC, ctx, { filename: "vba.js" });
  return { ctx, host, cells, log, form, done: () => doneMsg };
}
let n = 0;
function test(name, fn) { fn(); n++; console.log("ok - " + name); }

test("A1 / Cells / Range basics", () => {
  const t = makeHost([]);
  vm.runInContext(`Range("B3").Value = 42; Cells(1,1).Value = "x"; Range("C1:D2").Value = [[1,2],[3,"a"]];`, t.ctx);
  assert.strictEqual(t.cells["3,2"], "42");
  assert.strictEqual(vm.runInContext(`Cells(3,2).Value`, t.ctx), 42);
  assert.strictEqual(vm.runInContext(`Range("$B$3").Address`, t.ctx), "$B$3");
  assert.strictEqual(vm.runInContext(`Range("c1:d2").Address`, t.ctx), "$C$1:$D$2");
  assert.deepStrictEqual(JSON.parse(vm.runInContext(`JSON.stringify(Range("C1:D2").Value)`, t.ctx)), [[1, 2], [3, "a"]]);
  assert.strictEqual(vm.runInContext(`Cells(5,5).Value`, t.ctx), "");
  assert.strictEqual(vm.runInContext(`ColumnLetter(26)+ColumnLetter(27)+ColumnNumber("AA")`, t.ctx), "ZAA27");
  assert.throws(() => vm.runInContext(`Cells(0,1)`, t.ctx), /範囲外/);
  assert.throws(() => vm.runInContext(`Cells(1,27)`, t.ctx), /範囲外/);
  assert.throws(() => vm.runInContext(`Range("1A")`, t.ctx), /不正/);
  vm.runInContext(`Range("A10:A12").Value = 7; Cells(1,1).Offset(1,1).Value = true;`, t.ctx);
  assert.strictEqual(t.cells["12,1"], "7");
  assert.strictEqual(vm.runInContext(`Range("B2").Value`, t.ctx), true);
});

test("WorksheetFunction / LastRow", () => {
  const t = makeHost([]);
  vm.runInContext(`for (var i=1;i<=10;i++) Cells(i,1).Value=i; Cells(11,1).Value="text";`, t.ctx);
  assert.strictEqual(vm.runInContext(`WorksheetFunction.Sum(Range("A1:A20"))`, t.ctx), 55);
  assert.strictEqual(vm.runInContext(`WorksheetFunction.Average(Range("A1:A10"))`, t.ctx), 5.5);
  assert.strictEqual(vm.runInContext(`WorksheetFunction.Max(Range("A1:A20"), 99)`, t.ctx), 99);
  assert.strictEqual(vm.runInContext(`WorksheetFunction.CountA(Range("A1:A20"))`, t.ctx), 11);
  assert.strictEqual(vm.runInContext(`Sheet.LastRow("A")`, t.ctx), 11);
  assert.strictEqual(vm.runInContext(`Sheet.LastRow(2)`, t.ctx), 0);
});

test("string / number functions", () => {
  const t = makeHost([]);
  const ev = (s) => vm.runInContext(s, t.ctx);
  assert.strictEqual(ev(`Mid("abcdef",2,3)`), "bcd");
  assert.strictEqual(ev(`Left("abc",2)+Right("abc",2)`), "abbc");
  assert.strictEqual(ev(`InStr("hello","l")`), 3);
  assert.strictEqual(ev(`InStr(4,"hello","l")`), 4);
  assert.strictEqual(ev(`InStr("hello","z")`), 0);
  assert.strictEqual(ev(`CInt(2.5)`), 2);
  assert.strictEqual(ev(`CInt(3.5)`), 4);
  assert.strictEqual(ev(`Round(2.345, 2)`), 2.34);
  assert.strictEqual(ev(`Format(1234567.891, "#,##0.00")`), "1,234,567.89");
  assert.strictEqual(ev(`Format(0.256, "0.0%")`), "25.6%");
  assert.strictEqual(ev(`Format(5, "000")`), "005");
  assert.strictEqual(ev(`Format(new Date(2026,0,2,3,4,5), "yyyy/mm/dd hh:nn:ss")`), "2026/01/02 03:04:05");
  assert.strictEqual(ev(`Val("12abc")`), 12);
  assert.strictEqual(ev(`IsNumeric("1e3") && !IsNumeric("x") && !IsNumeric("")`), true);
  assert.throws(() => ev(`CDbl("x")`), /Type mismatch/);
});

test("MsgBox / InputBox via prompt channel", () => {
  const seen = [];
  const t = makeHost([o => { seen.push(o); return "6"; }, o => { seen.push(o); return "B"; }, o => { seen.push(o); return null; }]);
  assert.strictEqual(vm.runInContext(`MsgBox("q?", vbYesNo + vbQuestion, "T")`, t.ctx), 6);
  assert.deepStrictEqual(seen[0], { t: "msg", m: "q?", b: 4, title: "T" });
  assert.strictEqual(vm.runInContext(`InputBox("col", "t", "A")`, t.ctx), "B");
  assert.strictEqual(seen[1].d, "A");
  assert.strictEqual(vm.runInContext(`InputBox("col")`, t.ctx), "");  // cancel -> ""
});

test("__run calls Main, reports errors with line numbers", () => {
  const t = makeHost([]);
  t.ctx.__run(`var x = 1;\nfunction Main(){ Debug.Print("hi", x, true); }`);
  assert.strictEqual(t.done(), "");
  assert.deepStrictEqual(t.log, ["hi 1 TRUE"]);
  t.ctx.__run(`function Main(){\n  var a = 1;\n  undefinedThing();\n}`);
  assert.match(t.done(), /^行 3 \/ line 3: ReferenceError/);
});

test("UserForm buttons fire handlers", () => {
  const t = makeHost([]);
  t.ctx.__run(`var tb; function Main(){ tb = UserForm.AddTextBox("n","5"); UserForm.AddButton("go", function(){ Cells(1,1).Value = CDbl(tb.Value) * 2; }); }`);
  assert.strictEqual(t.done(), "");
  t.ctx.__fire(2);
  assert.strictEqual(t.cells["1,1"], "10");
});

test("sample macros run", () => {
  const S = path.join(ROOT, "samples");
  let t = makeHost([]);
  t.ctx.__run(fs.readFileSync(path.join(S, "01_kuku.js"), "utf8"));
  assert.strictEqual(t.done(), "");
  assert.strictEqual(t.cells["10,10"], "81");
  assert.ok(t.log[1].endsWith("2025"));
  t = makeHost([() => "a", () => "6", () => "1"]);
  t.ctx.__run(fs.readFileSync(path.join(S, "02_sum_column.js"), "utf8"));
  assert.strictEqual(t.done(), "");
  let sum = 0; for (let r = 1; r <= 10; r++) sum += Number(t.cells[r + ",1"]);
  assert.strictEqual(t.cells["11,1"], String(sum));
  t = makeHost([]);
  t.ctx.__run(fs.readFileSync(path.join(S, "03_form.js"), "utf8"));
  assert.strictEqual(t.done(), "");
  t.form[2] = "りんご"; t.form[3] = "120";
  t.ctx.__fire(4);
  assert.strictEqual(t.cells["2,2"], "りんご");
  assert.strictEqual(t.cells["2,3"], "120");
});
console.log(`\n${n} tests passed`);
