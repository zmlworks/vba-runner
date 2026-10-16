// サンプル2: 列の合計 / Sum a column
// 指定した列の数値を上から空白セルまで合計し、その下に書き込みます。
// 列が空なら乱数でデータを作ります。
function Main() {
  var col = UCase(InputBox("合計する列 (A〜Z)", "列の合計", "A"));
  if (col === "") return;                       // キャンセル
  var c = ColumnNumber(col);
  if (Sheet.LastRow(c) === 0) {
    if (MsgBox(col + " 列が空です。サンプルデータを作りますか?", vbYesNo + vbQuestion) !== vbYes) return;
    for (var r = 1; r <= 10; r++) Cells(r, c).Value = Int(Rnd() * 100);
  }
  var total = 0, n = 0, row = 1;
  while (Cells(row, c).Value !== "") {
    var v = Cells(row, c).Value;
    if (IsNumeric(v)) { total += v; n++; }
    row++;
  }
  Cells(row, c).Value = total;
  Cells(row, c).Offset(0, 1).Value = "← 合計";
  Debug.Print(col + "列:", n, "件, 合計", total, ", 平均", n ? Format(total / n, "0.00") : "-");
  MsgBox("合計: " + Format(total, "#,##0") + vbCrLf + "(" + n + " 件)", vbInformation, "結果");
}
