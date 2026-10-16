# スクリプト実行環境 (VBA風) — VBA-like Script Runner for Android

スマホの中だけで動く、Excel マクロ風のスクリプト実行環境です。コードエディタ・シート (26列 × 100行)・
フォーム・イミディエイトウィンドウを備え、`Cells(r, c).Value` や `MsgBox` / `InputBox` / `Debug.Print`
といった VBA 風の API を JavaScript から使えます。ネットワーク権限なし・広告なし・解析なし・AndroidX なし。

> このアプリは AI アシスタント **Grok Bot** が設計・実装しました (zmlworks の依頼による)。

## 機能
- **コード**: 等幅エディタ (行番号・自動インデント・記号キー列)。エラー時は該当行へジャンプ。
- **シート**: A〜Z × 1〜100 のグリッド (Canvas 描画、見出し固定、慣性スクロール)。タップで選択し、上部の欄で値を編集。
- **フォーム**: スクリプトから `UserForm.AddLabel / AddTextBox / AddButton` で部品を追加。ボタンは JS 関数を呼びます。
- **イミディエイト**: `Debug.Print` の出力と実行結果・エラー (行番号つき)。
- **MsgBox / InputBox**: ネイティブのダイアログ。スクリプトは VBA と同じように応答を待ちます (同期)。
- 保存/読み込み (アプリ専用領域)、ファイルへの書き出し/読み込み (SAF)、共有シート (テキスト)、シートの CSV 入出力。
- サンプル 3 本: 九九の表 / 列の合計 / 簡易家計簿フォーム。
- 他アプリから「共有」されたテキストをスクリプトとして開けます。

## API (抜粋)
```js
function Main() {                       // Main() があれば自動で呼ばれます
  Cells(1, 1).Value = "こんにちは";
  Range("B2:C3").Value = [[1, 2], [3, 4]];
  var s = WorksheetFunction.Sum(Range("B2:C3"));
  if (MsgBox("合計 " + s + " を A5 に書きますか?", vbYesNo) === vbYes) Range("A5").Value = s;
  var name = InputBox("名前は?", "入力", "zml");
  Debug.Print("name =", name, "last row of A =", Sheet.LastRow("A"));
}
```
`Cells / Range(.Value .Offset .Address .ClearContents .forEach) / Sheet.Clear / Sheet.LastRow /
WorksheetFunction.Sum Average Max Min Count CountA / MsgBox / InputBox / Debug.Print / UserForm.* /
Len Left Right Mid InStr Replace Split Join UCase LCase Trim CStr CInt CLng CDbl Val IsNumeric IsEmpty
Int Fix Abs Sqr Rnd Round Format Now ColumnLetter ColumnNumber` と `vbYesNo` などの定数。アプリ内の「APIヘルプ」にも一覧があります。

## サンドボックス
- マニフェストに **権限が 1 つもありません** (INTERNET もなし)。
- WebView: ファイル/コンテンツアクセス無効、`setBlockNetworkLoads(true)`、すべてのサブリソース要求を空応答で遮断、ページ遷移禁止。
- 実行ごとに WebView を作り直すので、グローバル変数は毎回リセットされます (VBA の「リセット」相当)。
- ■ ボタンで WebView ごと破棄 = 無限ループでも確実に止まります。

## なぜこの設計にしたか (Grok Bot より)
VBA らしさの核心は「`MsgBox` で処理が止まって答えを待つ」ことだと考えました。普通の JS ブリッジは非同期なので、
`window.prompt()` を WebView の `onJsPrompt` で横取りしてネイティブのダイアログを出し、ユーザーの応答を返すことで、
スクリプト側は本物の同期呼び出しのように書けます。シートの読み書きも `@JavascriptInterface` の同期メソッドで行い、
シートのデータ本体は Java 側に置きました (スクリプトが止まってもシートが残る・再描画を 60ms 単位でまとめられる)。
VBA の文法そのものを解釈するインタプリタも考えましたが、規模が大きくバグも増えるため、「書き方は JS、API と手触りは VBA」に絞りました。
グリッドは 2,600 セル分の View を作らず Canvas に直接描くことで、軽く速く保っています。AndroidX を使わないのも、APK を
数十 KB に抑えて起動を速くするためです。

## Why this design (English, short)
The essence of VBA is that `MsgBox`/`InputBox` *block* until answered. A normal JS bridge is async, so the runtime
routes dialogs through `window.prompt()`, intercepted by `WebChromeClient.onJsPrompt` to show a native dialog and return
the answer — scripts stay synchronous. Cells live in a Java model accessed via synchronous `@JavascriptInterface` calls.
I chose "JavaScript syntax with a VBA-shaped API" over writing a VBA interpreter: far smaller and less buggy.
The grid is drawn on a Canvas; no AndroidX, so the release APK is ~50 KB.

## ビルド / Build
JDK 17 + Android SDK (platform 35, build-tools 35.0.0)。
```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/   (applicationId に .debug が付き、リリース版と共存可)
./gradlew assembleRelease      # 署名用 keystore はリポジトリ外: ZML_KEYSTORE_PROPS か /workspace/android/keys/keystore.properties
./gradlew testDebugUnitTest lintDebug
node tools/test-vba.js         # vba.js (VBA風ヘルパー) のテスト (モックホスト使用)
```
keystore.properties の形式: `storeFile=… / storePassword=… / keyAlias=… / keyPassword=…`

## 既知の制限 / Known limitations
- 言語は JavaScript (VBA 構文ではありません)。`Sub`/`Dim`/`For…Next` は使えません。
- シートは 1 枚・固定サイズ (26 × 100)・値のみ (数式・書式・セル結合なし)。数値っぽい文字列は数値として読まれます。
- `Application.Wait` のような待機はありません (JS の `setTimeout` は使えます)。
- グローバル変数名に `top` / `name` / `status` / `parent` などブラウザの予約的な名前を使うと特別な挙動になります (WebView 上の JS のため)。関数内の `var` なら問題ありません。
- 構文ハイライトなし。端末の WebView (Chromium) の版により JS の対応機能が変わります。
- 実機・エミュレータでの動作確認は未実施です (ビルド・単体テスト・lint のみ。初回は実機で確認してください)。

## ライセンス
MIT © 2026 zmlworks — [LICENSE](LICENSE)。サードパーティ: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
