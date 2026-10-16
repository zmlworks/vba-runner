'@form name="カウンター (保存あり)"
'@Label Label1 x=16 y=24 w=328 h=96 caption="0" font=48
'@Button Button1 x=16 y=140 w=328 h=64 caption="＋1" font=24
'@Button Button2 x=16 y=220 w=160 h=48 caption="－1"
'@Button Button3 x=184 y=220 w=160 h=48 caption="リセット"
'@Label Label2 x=16 y=284 w=328 h=40 caption=""
' カウンター — Storage に保存するので、アプリを閉じても数が残ります
'  Storage.Get("キー", 既定値)  / Storage.Set "キー", 値

Dim count As Long

Sub Form_Load()
    count = Storage.Get("count", 0)
    Label2.Caption = "前回: " & Storage.Get("last", "(なし)")
    ShowCount
End Sub

Sub ShowCount()
    Label1.Caption = count
End Sub

Sub Save()
    Storage.Set "count", count
    Storage.Set "last", Format(Now, "yyyy/mm/dd hh:nn")
    ShowCount
End Sub

Sub Button1_Click()
    count = count + 1
    Save
End Sub

Sub Button2_Click()
    If count > 0 Then count = count - 1
    Save
End Sub

Sub Button3_Click()
    If MsgBox("0 に戻しますか?", vbYesNo + vbQuestion, "リセット") = vbYes Then
        count = 0
        Save
    End If
End Sub
