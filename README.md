# VBA風スクリプト — VBA-style scripts on Android

スマホの上で **VBA (Visual Basic for Applications) 風のスクリプト** を書いて、画面 (ボタン・入力欄・ドロップダウン・表) 付きで動かせるアプリです。
Excel は不要です。ネットワーク・広告・解析・Google Play 開発者サービスは一切使いません。権限もありません。

> このアプリは AI アシスタント **Grok Bot** が設計・実装しました (zmlworks の依頼による)。

## 使い方
1. 起動すると **スクリプト一覧**。タップで実行、長押しで 編集 / 名前変更 / 複製 / 書き出し / 共有 / 削除。右上 **＋** で新規。
2. **実行画面**: 上のバー (← 戻る / タイトル / ✎ 編集 / ■ 停止・▶ 再実行 / ⋮) 以外はすべてスクリプトの画面です。コードはここでは編集できません。
   `Debug.Print` の出力とエラーは下の **イミディエイト** 欄に出ます (⋮ で表示切替)。
3. **編集画面** はタブが2つ:
   - **画面作成**: ＋ラベル / ＋ボタン / ＋テキスト / ＋ドロップダウン / ＋表 で部品を追加。ドラッグで移動、ピンチ (または右下の角) で大きさ変更。
     選択中の部品をもう一度タップ → プロパティ (名前・表示文字・項目など)。「イベント」で `Sub Button1_Click()` などのひな型をコードに追加。
   - **コード**: VBA を書きます。入力のたびに構文チェックし、エラー行を表示します。▶ で保存して実行。
4. 実行時エラーは **行番号付きのダイアログ** と イミディエイト欄 に出ます。「コードを表示」でその行へ移動します。
5. AI に書いてもらう: ⋮ → **AI用の説明をコピー** で下の説明を (編集中ならそのスクリプトも) コピー → AI に要望と一緒に貼る →
   返ってきた ```vba ブロックをコピー → 一覧の ⋮ → **クリップボードから貼り付け**。

サンプル: 九九の表 / 足し算フォーム / ドロップダウン / 保存されるカウンター / あいさつ (InputBox・MsgBox)。

```vba
'@form name="足し算"
'@TextBox Text1 x=16 y=16 w=150 h=48
'@TextBox Text2 x=190 y=16 w=150 h=48
'@Button Button1 x=16 y=80 w=324 h=48 caption="計算"
'@Label Label1 x=16 y=144 w=324 h=40 caption=""
Sub Button1_Click()
    Label1.Caption = "合計: " & (Val(Text1.Value) + Val(Text2.Value))
End Sub
```

## 0.1.0 の「九九が動かない」不具合の原因
0.1.0 はスクリプトを JavaScript に変換して WebView で実行していました。外部アクセスを防ぐため
`shouldInterceptRequest` で **すべての** リクエストに空の 403 を返していましたが、このコールバックは `http(s):` だけでなく
`data:` / `file:` など全スキームで呼ばれます。`loadDataWithBaseURL` で読み込んだ実行用ページ自体も空の 403 に置き換わり、
インタプリタ (vba.js) が一度も読み込まれていませんでした。その後の `evaluateJavascript("__run(...)", null)` は
ReferenceError をコールバック無しで捨てるため、エラー表示もなく「実行中」のまま止まって見えました。
PC 側のテスト (Node / Chrome) はこの WebView 経路を通らないため見逃しました。

## 0.2.0 の設計 (なぜこの言語方式か)
WebView + JavaScript 変換をやめ、**Java で書いた VBA サブセットのインタプリタ** (字句解析 → 構文木 → 木を辿って実行) にしました。
- 端末の WebView のバージョンや設定に左右されず、エンジン全体を JVM の単体テストで確認できる (実機で初めて分かる失敗をなくす)。
- エラーの **行番号** と Sub 名を正確に出せる。`Stop` / ■ ボタンで確実に止められる (1 文ごとに停止要求を確認)。
- VBA の意味を JavaScript に寄せずにそのまま実装できる: `&` と `+` の違い、`\` / `Mod`、大文字小文字を区別しない名前、ByRef、
  銀行丸め (`Round`)、`Dim x As Integer` の型変換とオーバーフロー、`On Error Resume Next` など。
- スクリプトは別スレッドで動き、`MsgBox` / `InputBox` は本物の VBA と同じく **応答があるまで待ちます**。
- スクリプトごとに `Storage` (キーと値の保存領域、JSON) があり、次回起動時も値が残ります。

## Build
```sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
```
release 署名用 keystore はリポジトリ外 (`ZML_KEYSTORE_PROPS` または `/workspace/android/keys/keystore.properties`)。
テスト: インタプリタ (式・制御構文・型・エラー行・On Error・Format など) と実行系 (部品・イベント・MsgBox 待ち・停止・Storage・全サンプル・説明書の例)。

## 既知の制限 / Known limitations
- VBA の一部のみ: クラス・`With`・`GoTo`・`Type`・名前付き引数 (`:=`)・ユーザーフォームの細かいプロパティ・Excel のシート/セルはありません (表は `Grid` 部品)。
- 速度は本物の VBA より遅い (木を辿るインタプリタ)。重い計算は数秒かかることがあります (■ で停止可)。
- 画面は dp 単位の絶対配置。横向きや小さい画面では右端がはみ出すことがあります (縦スクロールは可)。
- 0.1.0 で作ったスクリプト (JavaScript 方式の保存形式) は引き継がれません。サンプルは入れ直されます。
- エミュレータが使えない環境で作ったため、画面操作 (ドラッグ・ピンチ・ダイアログ) は実機で未確認です。単体テスト・lint・ビルドのみ確認済み。

## English summary
Write and run VBA-style scripts with a simple form (buttons, text boxes, dropdowns, labels, grid) on Android, offline,
with no permissions. 0.2.0 replaces the 0.1.0 WebView/JavaScript engine (which never loaded because its request
interceptor also blocked the page's own data: URL) with a pure-Java VBA-subset interpreter: precise line numbers,
reliable Stop, blocking MsgBox/InputBox, per-script `Storage`. Tabs: designer (tap to add, drag to move, pinch to
resize) and code. The English language reference is in the app (? → help); the Japanese one below is meant for AI.

## AIに渡す用の説明
アプリ内の ⋮ → 「AI用の説明をコピー」と同じ内容です。下のブロックを丸ごとコピーして AI に渡してください。

````markdown
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
````

## ライセンス
MIT © 2026 zmlworks — [LICENSE](LICENSE)。サードパーティ: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
