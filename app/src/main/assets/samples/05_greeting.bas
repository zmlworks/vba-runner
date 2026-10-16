'@form name="あいさつ (MsgBox/InputBox)"
'@Label Label1 x=16 y=16 w=328 h=64 caption="ボタンを押すと名前を聞きます"
'@Button Button1 x=16 y=96 w=328 h=56 caption="あいさつする"
'@Button Button2 x=16 y=164 w=328 h=48 caption="終了 (End)"
' MsgBox / InputBox は答えが返るまでコードが止まります (本物の VBA と同じ)

Sub Button1_Click()
    Dim name As String
    name = InputBox("お名前は?", "あいさつ", "ゲスト")
    If name = "" Then
        Label1.Caption = "キャンセルされました"
        Exit Sub
    End If
    Dim n As Long
    n = Storage.Get("times", 0) + 1
    Storage.Set "times", n
    MsgBox "こんにちは、" & name & " さん!" & vbCrLf & n & " 回目のあいさつです。", vbInformation, "あいさつ"
    Label1.Caption = name & " さん (" & n & " 回目)"
    Debug.Print Format(Now, "hh:nn:ss") & " " & name
End Sub

Sub Button2_Click()
    MsgBox "スクリプトを終了します"
    End
End Sub
