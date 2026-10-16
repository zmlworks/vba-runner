# VBA風スクリプト (Android アプリ) — 仕様書 / AI 向け説明

このアプリは Android 上で **VBA のサブセット** を実行します (Excel は不要・使えません)。
1 つのスクリプト = 画面 (フォーム) のレイアウト + VBA コード で、**1 つのテキスト** に入ります。

## 出力形式 (AI へのお願い)
- スクリプト全体を **```vba のコードブロック 1 つ** で出力してください。
- 先頭に `'@` で始まるレイアウト行、その後に VBA コードを書きます。
- ユーザーはそれをコピーして、アプリの「⋮ → クリップボードから貼り付け」で取り込みます。

## レイアウト行
```
'@form name="スクリプト名"
'@<種類> <名前> x=<左> y=<上> w=<幅> h=<高さ> [オプション...]
```
- 種類: `Label` / `Button` / `TextBox` / `ComboBox` / `Grid`
- 名前の慣例: Label1, Button1, Text1, Combo1, Grid1 (英数字と _、日本語も可)
- 単位は dp。画面幅は約 360 なので **x + w ≤ 352** にしてください。縦は自由 (スクロールします)。
- ボタン・入力欄の高さは 48 以上が押しやすいです。
- オプション (値に空白があれば "..." で囲む。" は "" と書く):
  - `caption="..."` … Label / Button の表示文字
  - `text="..."` … TextBox の初期文字、ComboBox の初期選択
  - `items="りんご|みかん|ぶどう"` … ComboBox の項目 (| 区切り)
  - `rows=10 cols=3` … Grid の行数・列数
  - `multiline=true` … 複数行の TextBox
  - `font=16` … 文字サイズ (sp、既定 16)

## 実行の流れ
1. モジュールレベルのコード (Sub の外の Dim / Const / 文) を上から実行
2. `Sub Form_Load()` があれば実行 (なければ `Sub Main()`)
3. イベント待ち。部品を操作するとイベント Sub が呼ばれます:
   - `Button1_Click()` / `Label1_Click()`
   - `Text1_Change()` … ユーザーが文字を変えたとき
   - `Combo1_Change()` … ユーザーが項目を選んだとき
   - `Grid1_Click(Row, Col)` … 表のマスをタップ (1 から数える)
   - コードから値を変えてもイベントは起きません。
4. イベント Sub が 1 つもなければ、Form_Load / Main の後で終了します。
- モジュールレベルの変数 (`Dim count As Long` を Sub の外に書く) はイベント間で値が残ります。
- `End` または `Stop` でスクリプト全体が終了します。
- 実行時エラーが起きると、行番号付きのダイアログを出して停止します。

## 部品のプロパティ・メソッド
すべての部品: `.Visible` `.Enabled` `.Left` `.Top` `.Width` `.Height` `.FontSize` `.Name`(読取専用)
- **Label**: `.Caption` (既定プロパティ: `Label1 = "x"` も可)
- **Button**: `.Caption`
- **TextBox**: `.Text` / `.Value` (同じ。**常に文字列**)、`.Clear`
- **ComboBox**: `.Text` / `.Value` (選択中の文字、未選択は "")、`.ListIndex` (0 から、未選択 -1)、
  `.ListCount`、`.List(i)` (読み書き)、`.AddItem 文字 [, 位置]`、`.RemoveItem i`、`.Clear`
  - 項目があれば最初の項目が選ばれた状態で始まります。リストにない文字を `.Text` に入れるとエラー。
- **Grid** (表): `.Cells(行, 列)` (読み書き、1 から)、`.Rows`、`.Cols` (変更可)、`.Clear`
- **Form**: `Form.Caption` (上のバーの題名)、`Form.Width` / `Form.Height` (画面サイズ dp、読取専用)
- **Storage** (スクリプトごとの保存領域。アプリを閉じても残る):
  - `Storage.Get("キー" [, 既定値])` … 無ければ既定値 (省略時 Empty)
  - `Storage.Set "キー", 値` … 数値・文字列・True/False のみ (すぐ保存されます)
  - `Storage.Exists("キー")`、`Storage.Remove "キー"`、`Storage.Clear`、`Storage.Keys` (配列)、`Storage.Count`

## 言語 (VBA のサブセット)
- 大文字小文字は区別しません。コメントは `'` と `Rem`。行継続は ` _`。`:` で 1 行に複数文。
- `Option Explicit` (変数宣言を必須にする)
- `Dim a, b As Long, s As String, arr(10), m(1 To 3, 1 To 3)` / `ReDim [Preserve] arr(n)` / `Const PI = 3.14`
  - `As Integer/Long/Double/String/Boolean/Date` は代入時に型変換されます (TextBox の文字を数値に、など)。
  - 型なし (Variant) で `"1" + "2"` は `"12"` (文字列連結) になるので、数値は `As Long` / `As Double` か `CDbl()` で。
- `Sub 名前(引数) ... End Sub`、`Function 名前(引数) As 型 ... 名前 = 戻り値 ... End Function`
  - `ByVal` / `ByRef` (既定は ByRef)、`Optional x = 既定値`
  - 呼び出し: `Foo 1, 2` / `Call Foo(1, 2)` / `x = Bar(1)`
- `If 条件 Then ... ElseIf ... Else ... End If`、1 行 If (`If x > 0 Then y = 1 Else y = 2`)
- `For i = 1 To 10 [Step 2] ... Next [i]`、`For Each v In 配列 ... Next`
- `Do [While|Until 条件] ... Loop [While|Until 条件]`、`While ... Wend`
- `Select Case x` / `Case 1, 2` / `Case 3 To 5` / `Case Is >= 10` / `Case Else` / `End Select`
- `Exit Sub` / `Exit Function` / `Exit For` / `Exit Do`、`End`、`Stop`
- `On Error Resume Next` / `On Error GoTo 0`、`Err.Number`、`Err.Description`、`Err.Clear`、`Err.Raise 番号, , "説明"`
- 演算子: `+ - * / \ Mod ^ &`、`= <> < > <= >=`、`And Or Not Xor`、`Like` (`* ? # [a-z] [!a]`)、`Is`
- 定数: `True False Empty Nothing vbCrLf vbNewLine vbCr vbLf vbTab`、
  `vbOKOnly vbOKCancel vbYesNoCancel vbYesNo vbRetryCancel vbAbortRetryIgnore`、
  `vbCritical vbQuestion vbExclamation vbInformation`、`vbOK vbCancel vbAbort vbRetry vbIgnore vbYes vbNo`
- `Debug.Print 値[; 値][, 値]` → 画面下の「イミディエイト」欄に出力

## 組み込み関数
- ダイアログ (答えが返るまでコードは止まります):
  - `MsgBox(文字 [, ボタン+アイコン [, 題名]])` → vbOK / vbYes / vbNo など
  - `InputBox(文字 [, 題名 [, 既定値]])` → 入力文字 (キャンセルは "")
- 文字列: `Len Left Right Mid InStr InStrRev Replace Trim LTrim RTrim UCase LCase Split Join StrReverse
  String Space Chr Asc StrComp Format Hex Filter`
- 変換・判定: `CStr CInt CLng CDbl CBool CDate Val Str IsNumeric IsEmpty IsArray IsDate IsMissing TypeName VarType IIf Choose Switch`
- 数学: `Abs Int Fix Round (偶数丸め) Sqr Sgn Exp Log Sin Cos Tan Atn Rnd Randomize Max Min`
- 配列: `Array UBound LBound` (`Array()` と `Split()` は 0 から)
- 日付: `Now Date Time Timer Year Month Day Hour Minute Second Weekday WeekdayName MonthName
  DateSerial TimeSerial DateAdd DateDiff DateValue TimeValue` (間隔: "yyyy" "q" "m" "d" "ww" "h" "n" "s")
- `Format(値, "0.00")`、`"#,##0"`、`"0%"`、`"yyyy/mm/dd hh:nn:ss"`、`"yyyy年m月d日(aaa)"` など
- `Sleep ミリ秒`、`DoEvents`

## 使えないもの
Excel のオブジェクト (Range / Cells / Worksheets / Application / ActiveSheet)、UserForm.Show、
クラスモジュール、`With`、`GoTo` / `GoSub` / `On Error GoTo ラベル`、`Type`、`Declare`、`New` / `CreateObject`、
ファイル入出力 (`Open`、FileSystemObject)、ネットワーク、名前付き引数 (`x:=1`)、`Next i, j`、Collection / Dictionary。
代わりに、表は Grid、保存は Storage を使ってください。

## 例: 足し算
```vba
'@form name="足し算"
'@TextBox Text1 x=16 y=16 w=150 h=48
'@TextBox Text2 x=186 y=16 w=150 h=48
'@Button Button1 x=16 y=80 w=320 h=48 caption="計算"
'@Label Label1 x=16 y=144 w=320 h=48 caption="答え:"

Sub Button1_Click()
    If Not IsNumeric(Text1.Value) Or Not IsNumeric(Text2.Value) Then
        MsgBox "数値を入力してください", vbExclamation
        Exit Sub
    End If
    Label1.Caption = "答え: " & (CDbl(Text1.Value) + CDbl(Text2.Value))
End Sub
```
