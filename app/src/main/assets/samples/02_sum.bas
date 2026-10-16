'@form name="足し算フォーム"
'@Label Label3 x=16 y=16 w=328 h=32 caption="2つの数を入れて「計算」を押してください"
'@TextBox Text1 x=16 y=56 w=140 h=48
'@Label Label2 x=164 y=56 w=32 h=48 caption="＋"
'@TextBox Text2 x=204 y=56 w=140 h=48
'@Button Button1 x=16 y=120 w=328 h=52 caption="計算"
'@Label Label1 x=16 y=188 w=328 h=56 caption="答え:" font=24
' 足し算 — テキストボックス2つ + ボタン + ラベル

Sub Button1_Click()
    If Not IsNumeric(Text1.Value) Or Not IsNumeric(Text2.Value) Then
        MsgBox "数値を入力してください", vbExclamation, "入力エラー"
        Exit Sub
    End If
    Dim a As Double, b As Double
    a = Text1.Value        ' As Double なので数値に変換される
    b = Text2.Value
    Label1.Caption = "答え: " & (a + b)
End Sub

' 入力が変わったら答えを消す
Sub Text1_Change()
    Label1.Caption = "答え:"
End Sub

Sub Text2_Change()
    Label1.Caption = "答え:"
End Sub
