// サンプル1: 九九の表 / Multiplication table
// シートの A1:J10 に九九の表を書き込みます。
function Main() {
  Sheet.Clear();
  Cells(1, 1).Value = "×";
  for (var i = 1; i <= 9; i++) {
    Cells(1, i + 1).Value = i;   // 見出し行
    Cells(i + 1, 1).Value = i;   // 見出し列
    for (var j = 1; j <= 9; j++) {
      Cells(i + 1, j + 1).Value = i * j;
    }
  }
  Debug.Print("九九の表を書き込みました:", Range("A1:J10").Address);
  Debug.Print("合計 =", WorksheetFunction.Sum(Range("B2:J10")));  // 2025
}
