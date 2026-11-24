# MyKeyboard

Kotlin製のAndroid IMEプロジェクトです。独自Canvas UI、12キーの日本語フリック入力、QWERTY入力、長押し入力、InputConnectionによる文字入力、GitHub ActionsのDebug APK生成を含みます。

## 現在の実装状態

**重要：このZIPはIME本体の初期実装です。Mozcのかな漢字変換エンジンは未統合です。**
12キーではかなを composing text として入力できますが、かな漢字変換候補の取得・表示・選択・確定は未実装です。画面にも未接続であることを表示します。変換機能があるように見せるダミー候補は表示しません。

## 開発環境

- Android Studio（Koala以降を推奨）
- JDK 17
- Android SDK Platform 35
- Android Gradle Plugin 8.7.3
- Kotlin 2.0.21
- Gradle Wrapper 8.9
- minSdk 23 / targetSdk 35

## ビルド

Android Studioでこのフォルダーを開き、Gradle Sync後に `Build > Build Bundle(s) / APK(s) > Build APK(s)` を選択します。

コマンドラインの場合：

```bash
./gradlew assembleDebug
```

APK出力先：`app/build/outputs/apk/debug/app-debug.apk`

## インストール・有効化

1. APKをAndroid端末へコピーしてインストールします。
2. MyKeyboardアプリを開き「キーボード設定を開く」を押します。
3. 一覧から「MyKeyboard（試作）」を有効にします。
4. テキスト入力欄を開き、キーボード切替ボタンからMyKeyboardを選びます。
5. キーボード上部をタップすると12キーとQWERTYを切り替えられます。

Androidのメーカー・OSバージョンによって設定画面の表記は異なります。

## GitHub Actions

GitHubで空のリポジトリを作成し、ZIPを展開した中身をリポジトリ直下へアップロードしてください。`.github/workflows/android.yml` が含まれていることを確認して `main` ブランチへコミットするとActionsが実行されます。

`Actions > Android Debug APK > 最新の実行 > Artifacts > MyKeyboard-debug-apk` からAPKを取得できます。手動実行は `Actions > Android Debug APK > Run workflow` です。

## Release署名

署名鍵（keystore）やパスワードはGitHub Secretsに保存してください。ソースコードに書かないでください。この初期版のWorkflowはDebug APKのみを生成します。Release署名Workflowは未設定です。

## Mozc統合について

公式ソース：https://github.com/google/mozc

Mozc公式READMEではGoogle作成コードをBSD 3-Clauseとしていますが、辞書データ・サードパーティコードには別のライセンス条件があります。配布時は各ディレクトリのライセンス表記を同梱・確認してください。

現行MozcのAndroidネイティブライブラリ生成手順（Linux/macOS）：

```bash
git clone https://github.com/google/mozc.git
cd mozc/src
python3 build_tools/update_deps.py
bazelisk build package --config oss_android --config release_build
```

この手順は `libmozc.so` のビルドであり、独自IME向けの変換セッションAPI統合までを行うものではありません。公式リポジトリの説明では旧Androidクライアントコードはdeprecatedです。現行ライブラリのJNI公開API・セッション生成・候補取得・候補選択・確定処理を別途実装し、実機テストする必要があります。Mozc統合済みとは扱っていません。

## 主要ファイル

- `MyInputMethodService.kt`：IMEサービスとInputConnection処理
- `KeyboardView.kt`：独自描画、フリック・長押し判定、配列
- `MainActivity.kt`：IME有効化・切替画面への導線
- `AndroidManifest.xml` / `res/xml/method.xml`：IME登録
- `.github/workflows/android.yml`：CIビルド

## 制限・既知事項

- Mozcかな漢字変換、候補取得・選択は未実装。
- QWERTYの大文字は各英字キーの下フリックで入力します。Shift状態保持はありません。
- QWERTYの数字・記号は各英字キーの上フリックで入力します。
- 12キーの長押しは中央タップ相当で数字、方向フリックで記号を入力します。
- 端末固有のIME動作・全アプリ互換性は実機での検証が必要です。
- カーソル移動は左右キーイベントを送信します。アプリによって挙動が異なる場合があります。

## ライセンス

このプロジェクトのKotlinコードはMIT Licenseとします。Mozcソースコードや辞書データは同梱していません。将来Mozcを組み込む場合はMozc本体および同梱依存物のライセンスを個別に遵守してください。
