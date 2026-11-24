# Wi-Fiマナーモード

登録済みWi-Fiでは通常モード、それ以外ではバイブレーションモードに切り替えるAndroidアプリです。

## ビルド
GitHub Actions の「Build Wi-Fi Silent Mode APK」から実行し、`wifi-silent-mode-debug` Artifactをダウンロードしてください。

## 注意
SSID取得はAndroidのバージョン・権限・端末設定の影響を受けます。位置情報そのものは判定に使用しませんが、OS仕様上、SSID取得に位置情報関連権限や端末設定が必要な場合があります。メーカー独自の省電力設定によってバックグラウンド動作が制限される場合があります。
