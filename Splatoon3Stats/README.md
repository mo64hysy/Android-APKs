# Splatoon3Stats (prototype)

Android版「Nintendo Switch Online」アプリ内のイカリング3画面を、Androidの画面キャプチャ機能で取得し、日本語OCRでテキスト化してChatGPTなどへ共有する試作です。

## 使い方

1. Splatoon3Statsで「画面キャプチャを準備する」を押し、Androidの画面共有を許可します。
2. Nintendo Switch Onlineアプリに切り替え、イカリング3の読み取りたいバトル履歴画面を表示します。
3. 通知パネルを開き、Splatoon3Stats通知の「今の画面を読み取る」を押します。
4. 読み取り完了後にSplatoon3Statsへ戻り、OCR結果を確認します。
5. 「GPT分析用テキストを共有」からChatGPTなどへ送ります。

## 機能

- Android MediaProjectionによる画面キャプチャ（ユーザーの許可が必要）
- ML Kit日本語テキスト認識によるOCR
- OCR結果の画面表示・コピー
- Android共有シートによるChatGPTなどへのテキスト共有

## 注意点・制約

- キャプチャ開始はAndroidのシステム確認を経て、ユーザーが明示的に許可した場合のみ実行します。
- キャプチャ対象は、通知の読み取り操作を行った時点で画面に表示されている内容です。
- OCRは文字の小ささ、表示倍率、装飾、スクロール位置などにより誤認識や読み漏れが発生します。共有前に結果を確認してください。
- 画面画像やOCR結果を外部サーバーへ送信しません。日本語認識は端末内で処理します。
- Nintendoアカウントの認証情報にはアクセスしません。

## ビルド

GitHub Actionsの「Build Splatoon3Stats APK」を実行するか、Splatoon3StatsフォルダをAndroid Studioで開いてください。
