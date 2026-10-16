#!/bin/sh
# Runs vba.js in headless Chrome (same engine family as Android WebView) to exercise the DOM path of __run:
# <script>-element execution, window "error" events with line numbers, Main(), prompt()-based dialogs.
# Usage: tools/test-vba-browser.sh   (needs google-chrome or chromium)
set -e
cd "$(dirname "$0")/.."
CHROME=$(command -v google-chrome || command -v chromium || command -v chromium-browser)
OUT=$(mktemp -d)
{
cat <<'H'
<!doctype html><html><head><meta charset="utf-8"></head><body><pre id="out">running</pre><script>
var cells = {}, log = [], dones = [], answers = ["6", "zml"];
window.__host = {
  rows: function(){return 100}, cols: function(){return 26},
  get: function(r,c){return cells[r+","+c]||""}, set: function(r,c,v){cells[r+","+c]=v},
  getBlock: function(){return "[]"}, setBlock: function(){}, clear: function(){cells={}},
  lastRow: function(){return 0}, print: function(s){log.push(s)}, error: function(s){log.push("ERR "+s)},
  begin: function(){}, done: function(m){dones.push(m)},
  formClear: function(){}, formShow: function(){}, formAddLabel: function(){return 1},
  formAddTextBox: function(){return 2}, formAddButton: function(){return 3}, formGet: function(){return ""}, formSet: function(){}
};
window.prompt = function(m){ return m.indexOf("@@VBA@@") === 0 ? answers.shift() : null; };
</script><script>
H
cat app/src/main/assets/vba.js
cat <<'H'
</script><script>
var results = [];
function check(name, ok, detail){ results.push((ok ? "ok - " : "FAIL - ") + name + (ok ? "" : " :: " + detail)); }
__run('function Main(){\n Cells(1,1).Value = MsgBox("q", vbYesNo) === vbYes ? InputBox("n") : "no";\n Debug.Print("x", 1);\n}');
check("Main + MsgBox + InputBox", cells["1,1"] === "zml" && dones[0] === "" && log[0] === "x 1", JSON.stringify([cells, dones, log]));
__run('var a = 1;\nvar b = ;\n');
check("syntax error has line number", /^行 2 \/ line 2: SyntaxError: Unexpected token/.test(dones[1]), dones[1]);
__run('function Main(){\n\n  nope();\n}');
check("runtime error has line number", /^行 3 \/ line 3: ReferenceError/.test(dones[2]), dones[2]);
__run('var five = 5; Cells(2,2).Value = five * 2;');
check("top-level code without Main", cells["2,2"] === "10" && dones[3] === "", JSON.stringify(dones));
__run('setTimeout(function(){ throw new Error("late"); }, 0);');
setTimeout(function(){
  check("async error reported via H.error", log.some(function(l){return /^ERR .*late/.test(l)}), JSON.stringify(log));
  document.getElementById("out").textContent = results.join("\n");
}, 100);
</script></body></html>
H
} > "$OUT/t.html"
"$CHROME" --headless=new --disable-gpu --no-sandbox --virtual-time-budget=3000 --dump-dom "file://$OUT/t.html" 2>/dev/null \
  | sed 's/.*<pre id="out">//; s/<\/pre>.*//' | grep -E '^(ok|FAIL) - |^running' > "$OUT/r.txt"
cat "$OUT/r.txt"
! grep -q "FAIL\|running" "$OUT/r.txt" && [ -s "$OUT/r.txt" ] && echo "browser tests passed"
