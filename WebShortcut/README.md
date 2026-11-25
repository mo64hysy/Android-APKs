# Web Shortcut

AndroidアプリからURL・名前・アイコンを登録し、ホーム画面に専用ショートカットを作成します。ショートカットは元アプリとは別のAndroidタスク（ドキュメントウィンドウ）でWebViewを起動します。

## 仕様
- 本物のPWAインストールは行いません。
- 起動ごとに登録URLをWebViewで読み込みます。
- CookieはAndroid WebViewのCookieManagerで保持します。
- アイコン画像を選択できます。
- 任意で `.user.js` を選択できます。現バージョンは `@match` / `@include` / `@exclude` を使ったページ読み込み後の実行に対応します。
- Tampermonkey互換のGM_* API、document-start実行、拡張機能APIは未対応です。

## ビルド
```sh
gradle assembleDebug
```
APK: `app/build/outputs/apk/debug/app-debug.apk`

## 注意
UserScriptは登録したサイト内でJavaScriptを実行します。信頼できるスクリプトだけを指定してください。
