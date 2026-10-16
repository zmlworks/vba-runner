// サンプル3: 小さなフォーム (簡易家計簿) / A little UserForm
// 品目と金額を入力して「追加」するとシートの次の空き行に記録します。
var item, amount;

function nextRow() { return Sheet.LastRow("A") + 1; }

function Main() {
  if (Range("A1").Value === "") Range("A1:C1").Value = [["日付", "品目", "金額"]];
  UserForm.Clear();
  UserForm.AddLabel("簡易家計簿");
  item = UserForm.AddTextBox("品目", "");
  amount = UserForm.AddTextBox("金額", "");
  UserForm.AddButton("追加", function () {
    if (!IsNumeric(amount.Value)) { MsgBox("金額は数値で入力してください", vbExclamation); return; }
    var r = nextRow();
    Cells(r, 1).Value = Format(Now(), "yyyy/mm/dd");
    Cells(r, 2).Value = item.Value;
    Cells(r, 3).Value = CDbl(amount.Value);
    Debug.Print("追加:", r, "行目", item.Value, amount.Value);
    item.Value = ""; amount.Value = "";
  });
  UserForm.AddButton("合計を表示", function () {
    var last = Sheet.LastRow("C");
    var sum = last >= 2 ? WorksheetFunction.Sum(Range("C2:C" + last)) : 0;
    MsgBox("合計: " + Format(sum, "#,##0") + " 円", vbInformation, "家計簿");
  });
  UserForm.Show();
}
