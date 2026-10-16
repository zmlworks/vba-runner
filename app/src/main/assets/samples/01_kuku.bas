'@form name="九九の表"
'@Label Label1 x=8 y=8 w=344 h=40 caption="マスをタップすると式が出ます"
'@Grid Grid1 x=8 y=56 w=344 h=344 rows=10 cols=10
'@Button Button1 x=8 y=412 w=168 h=48 caption="イミディエイトに出力"
'@Button Button2 x=184 y=412 w=168 h=48 caption="クリア"
' 九九の表 — Grid(表) コントロールの例
' Form_Load は起動直後に1回だけ実行されます。

Sub Form_Load()
    Dim i As Integer, j As Integer
    Grid1.Cells(1, 1) = "×"
    For i = 1 To 9
        Grid1.Cells(1, i + 1) = i      ' 見出し行
        Grid1.Cells(i + 1, 1) = i      ' 見出し列
        For j = 1 To 9
            Grid1.Cells(i + 1, j + 1) = i * j
        Next j
    Next i
End Sub

' 表のマスをタップしたとき (Row, Col は 1 から)
Sub Grid1_Click(Row, Col)
    If Row = 1 Or Col = 1 Then Exit Sub
    Label1.Caption = (Row - 1) & " × " & (Col - 1) & " = " & Grid1.Cells(Row, Col)
End Sub

' 文字だけの九九を Debug.Print で出力
Sub Button1_Click()
    Dim i As Integer, j As Integer, s As String
    For i = 1 To 9
        s = ""
        For j = 1 To 9
            s = s & Right("  " & (i * j), 3)
        Next j
        Debug.Print s
    Next i
End Sub

Sub Button2_Click()
    Grid1.Clear
    Label1.Caption = "クリアしました"
    Form_Load
End Sub
