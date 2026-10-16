'@form name="ドロップダウン"
'@Label Label2 x=16 y=16 w=328 h=32 caption="果物を選んでください"
'@ComboBox Combo1 x=16 y=56 w=328 h=48 items="りんご|みかん|ぶどう"
'@Label Label1 x=16 y=116 w=328 h=48 caption="" font=20
'@TextBox Text1 x=16 y=184 w=212 h=48
'@Button Button1 x=236 y=184 w=108 h=48 caption="追加"
' ドロップダウン (ComboBox) の例
'  Combo1.Text      … 選ばれている文字
'  Combo1.ListIndex … 選ばれている番号 (0 から、未選択は -1)
'  Combo1.AddItem   … 項目を追加

Function Price(name)
    Select Case name
        Case "りんご": Price = 150
        Case "みかん": Price = 80
        Case "ぶどう": Price = 400
        Case Else: Price = 0
    End Select
End Function

Sub Form_Load()
    ShowPrice
End Sub

Sub Combo1_Change()
    ShowPrice
End Sub

Sub ShowPrice()
    Dim p
    p = Price(Combo1.Text)
    If p = 0 Then
        Label1.Caption = Combo1.Text & " (価格未登録)"
    Else
        Label1.Caption = Combo1.Text & " は " & Format(p, "#,##0") & " 円"
    End If
End Sub

Sub Button1_Click()
    Dim s As String
    s = Trim(Text1.Text)
    If s = "" Then
        MsgBox "追加する名前を入力してください"
        Exit Sub
    End If
    Combo1.AddItem s
    Combo1.ListIndex = Combo1.ListCount - 1
    Text1.Text = ""
    ShowPrice
End Sub
