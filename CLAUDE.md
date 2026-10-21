# Acrylterm 開発メモ(エージェント向け)

## 隔離方針
- 既定は **main + ブランチ**。セッション単位の worktree は切らない。
  - 理由:gitignore された開発必需品(`app/libs/tsbridge.aar`、`local.properties`)があり、実機確認も伴うため。
- 並列ワーカーは Gradle を同時に走らせない(同じプロジェクトディレクトリでロックが競合する)。ビルドは 1 本ずつ。

## ビルド・検証
- `bash scripts/verify.sh`:`:app:assembleDebug` を実行し、ログを `.verify/build.log` に書く(Android Studio 同梱の JDK を使う)。
  - 結果確認:`grep -nE "BUILD|^e: |^w: |What went wrong" .verify/build.log`
- リリース APK:`./gradlew :app:assembleRelease`。署名は gitignore された `keystore.properties`(鍵本体は `~/.android/acrylterm-release.jks`)を使い、無ければデバッグ鍵にフォールバックする。GitHub Release に載せる APK は必ず本番鍵で署名する(デバッグ鍵の APK とは上書き更新できない)。
- ユニットテスト:`./gradlew :app:testDebugUnitTest`(`app/src/test/`、純粋ロジックのみ)。`verify.sh` には含まれない。
- Tailscale 内蔵:`./tsbridge/build.sh` で `app/libs/tsbridge.aar` を生成する(NDK 30.0.16248370 を導入済み、所要は数分)。AAR が無くてもアプリはビルドできる。
- ライセンス:依存(`app/build.gradle.kts` / `tsbridge/go.mod`)を変えたら `scripts/gen-go-licenses.sh` を実行して `app/config/` の差分をコミットする(Android 側は AboutLibraries がビルド時に自動収集。Go が無い環境でもビルドできるよう Go 側は生成物をコミット)。
- 実機:`./gradlew :app:installDebug`(USB デバッグで接続した端末)。Gradle には `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"` が必要。
  - Wi-Fi 経由:端末でワイヤレス デバッグを有効にし、`adb mdns services` で `ip:port` を確認して `adb connect <ip:port>` してから同じコマンドを使う(ペアリング済み)。

## 文字列(i18n)
- UI 文字列は Kotlin に直書きしない。`res/values/strings_<area>.xml`(英語、既定ロケール)と `res/values-ja/strings_<area>.xml`(日本語)の両方に同じキーで足す。area は `term` / `herdrui` / `ext` / `settings` / `core`。
- Composable では `stringResource`、Context の無い非 UI コードでは `App.str` / `App.plural`(JVM テストでは `"string/<id>"` を返す)。
- 新しい言語は `values-<locale>/` を足し、`res/xml/locales_config.xml` に `<locale>` を追加する。
- リモートに送るコマンドや AI CLI へのプロンプト(`ExtSync` など)は訳さない。
