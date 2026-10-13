# androidTerminal 開発メモ(エージェント向け)

## 隔離方針
- 既定は **main + ブランチ**。セッション単位の worktree は切らない。
  - 理由:gitignore された開発必需品(`app/libs/tsbridge.aar`、`local.properties`)があり、実機確認も伴うため。
- 並列ワーカーは Gradle を同時に走らせない(同じプロジェクトディレクトリでロックが競合する)。ビルドは 1 本ずつ。

## ビルド・検証
- `bash scripts/verify.sh`:`:app:assembleDebug` を実行し、ログを `.verify/build.log` に書く(Android Studio 同梱の JDK を使う)。
  - 結果確認:`grep -nE "BUILD|^e: |^w: |What went wrong" .verify/build.log`
- ユニットテスト:`./gradlew :app:testDebugUnitTest`(`app/src/test/`、純粋ロジックのみ)。`verify.sh` には含まれない。
- Tailscale 内蔵:`./tsbridge/build.sh` で `app/libs/tsbridge.aar` を生成する(NDK 30.0.16248370 を導入済み、所要は数分)。AAR が無くてもアプリはビルドできる。
- 実機:`./gradlew :app:installDebug`(USB デバッグで接続した端末)。Gradle には `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"` が必要。
  - Wi-Fi 経由:端末でワイヤレス デバッグを有効にし、`adb mdns services` で `ip:port` を確認して `adb connect <ip:port>` してから同じコマンドを使う(ペアリング済み)。
