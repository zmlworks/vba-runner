# VBA-like Script (Android app) — specification / description for an AI

This app runs a **subset of VBA** on Android (no Excel available).
One script = a form layout + VBA code, stored as **one plain text**.

## Output format (for the AI)
- Output the whole script as **one ```vba code block**.
- Start with layout lines beginning with `'@`, followed by VBA code.
- The user copies it and imports it via "⋮ → Paste from clipboard".

## Layout lines
```
'@form name="Script name"
'@<Type> <Name> x=<left> y=<top> w=<width> h=<height> [options...]
```
- Types: `Label` / `Button` / `TextBox` / `ComboBox` / `Grid`
- Naming convention: Label1, Button1, Text1, Combo1, Grid1 (letters, digits, _)
- Units are dp. The screen is about 360 wide, so keep **x + w ≤ 352**. Height is unlimited (scrolls).
- Buttons/inputs should be at least 48 high.
- Options (quote values with spaces; write " as ""):
  `caption="..."` (Label/Button), `text="..."` (TextBox initial text, ComboBox initial selection),
  `items="a|b|c"` (ComboBox), `rows=10 cols=3` (Grid), `multiline=true` (TextBox), `font=16` (sp)

## Execution
1. Module-level code (Dim / Const / statements outside Subs) runs top to bottom.
2. `Sub Form_Load()` runs if present (otherwise `Sub Main()`).
3. Then the script waits for events:
   `Button1_Click()`, `Label1_Click()`, `Text1_Change()` (user typed), `Combo1_Change()` (user picked),
   `Grid1_Click(Row, Col)` (1-based). Changing values from code does not raise events.
4. Without any event Sub the script ends after Form_Load / Main.
- Module-level variables keep their values between events. `End` / `Stop` ends the script.
- Run-time errors stop the script and show a dialog with the line number.

## Controls
All: `.Visible .Enabled .Left .Top .Width .Height .FontSize .Name`(read-only)
- **Label**: `.Caption` (default property)  — **Button**: `.Caption`
- **TextBox**: `.Text` / `.Value` (always a string), `.Clear`
- **ComboBox**: `.Text` / `.Value`, `.ListIndex` (0-based, -1 = none), `.ListCount`, `.List(i)`, `.AddItem s [, index]`,
  `.RemoveItem i`, `.Clear` (starts with the first item selected; setting `.Text` to a value not in the list is an error)
- **Grid**: `.Cells(row, col)` (read/write, 1-based), `.Rows`, `.Cols`, `.Clear`
- **Form**: `Form.Caption` (title bar), `Form.Width` / `Form.Height` (dp, read-only)
- **Storage** (per-script, persistent): `Storage.Get("key" [, default])`, `Storage.Set "key", value`
  (numbers, strings, True/False), `Storage.Exists`, `Storage.Remove`, `Storage.Clear`, `Storage.Keys`, `Storage.Count`

## Language (VBA subset)
Case-insensitive; `'`/`Rem` comments; ` _` continuation; `:` separator; `Option Explicit`;
`Dim x As Long, a(10), m(1 To 3, 1 To 3)`, `ReDim [Preserve]`, `Const`; typed variables convert on assignment
(untyped `"1" + "2"` is `"12"`, so use `As Long`/`As Double` or `CDbl()` for numbers);
`Sub` / `Function` with `ByVal`/`ByRef`/`Optional`; `If/ElseIf/Else/End If` and one-line If; `For/Next`, `For Each`;
`Do/Loop While|Until`, `While/Wend`; `Select Case` (lists, `To`, `Is >=`, `Case Else`); `Exit Sub/Function/For/Do`;
`End`, `Stop`; `On Error Resume Next` / `GoTo 0`, `Err.Number/Description/Clear/Raise`;
operators `+ - * / \ Mod ^ & = <> < > <= >= And Or Not Xor Like Is`; vb constants (vbCrLf, vbYesNo, vbYes, …);
`Debug.Print` writes to the Immediate pane.

## Built-in functions
MsgBox, InputBox (both block until answered; InputBox returns "" on cancel), Len Left Right Mid InStr InStrRev Replace
Trim LTrim RTrim UCase LCase Split Join StrReverse String Space Chr Asc StrComp Format Hex Filter, CStr CInt CLng CDbl
CBool CDate Val Str IsNumeric IsEmpty IsArray IsDate IsMissing TypeName VarType IIf Choose Switch, Abs Int Fix Round
(banker's) Sqr Sgn Exp Log Sin Cos Tan Atn Rnd Randomize Max Min, Array UBound LBound, Now Date Time Timer Year Month
Day Hour Minute Second Weekday WeekdayName MonthName DateSerial TimeSerial DateAdd DateDiff DateValue TimeValue,
Sleep, DoEvents. Format supports "0.00", "#,##0", "0%", "yyyy/mm/dd hh:nn:ss", "aaa" (Japanese weekday).

## Not supported
Excel objects (Range, Cells, Worksheets, Application), UserForm.Show, classes, With, GoTo/GoSub/On Error GoTo label,
Type, Declare, New/CreateObject, file I/O, network, named arguments, `Next i, j`, Collection/Dictionary.
Use Grid for tables and Storage for saving.

## Example
```vba
'@form name="Sum"
'@TextBox Text1 x=16 y=16 w=150 h=48
'@TextBox Text2 x=186 y=16 w=150 h=48
'@Button Button1 x=16 y=80 w=320 h=48 caption="Add"
'@Label Label1 x=16 y=144 w=320 h=48 caption="Result:"

Sub Button1_Click()
    If Not IsNumeric(Text1.Value) Or Not IsNumeric(Text2.Value) Then
        MsgBox "Please enter numbers", vbExclamation
        Exit Sub
    End If
    Label1.Caption = "Result: " & (CDbl(Text1.Value) + CDbl(Text2.Value))
End Sub
```
