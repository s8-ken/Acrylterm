# Cyan Veil

[Figma デザイン](https://www.figma.com/design/2TbuuCmJydXEP8JltDlZBq?node-id=2-2) を Android / Jetpack Compose に反映した UI 案です。

- ホスト一覧 `4:32`、ターミナル `4:453`、タブ一覧 `5:10`、外観設定 `5:85` を参照。
- 黒緑 `#071216`、シアン `#64F4E5`、ミント `#9CF6C9`。入力待ちはアンバー、エラーはローズ。
- 主要カードは半径 20 dp、補助キーは 12 dp、主要ボタンはピル型。タッチ領域は原則 48 dp 以上。
- 端末フォントは既存の UDEV Gothic を維持。Figma 上の代替フォントを端末には持ち込んでいません。
- 背景透明度の初期値は 28%。既定背景だけを透過し、ANSI の明示的背景色・文字・カーソルは透過しません。
- ぼかしはアプリ内のグラデーション壁紙のみ。OS のホーム画面や他のアプリが透ける機能ではありません。
- 設定はプレビュー後に適用して保存。戻る操作は変更を破棄します。

`icons/*.svg` は Figma が返したアイコン素材です。Android SDK の `Svg2Vector` で `app/src/main/res/drawable/veil_*.xml` に変換し、元のパスと縦横比を維持しています。画面全体の画像貼り付けは使っていません。

## 動作上の調整

一覧は縦スクロールなので、上スワイプによるタブ閉じは長押しで編集モードに入ってから行います。閉じるボタン・アクセシビリティ操作も同じ確認ダイアログを経由します。ワークスペースで絞り込んだ状態の「新しいタブ」は、そのワークスペースに作成します。

小さい画面やキーボード表示時にも端末領域を確保するため、IME 表示中はパンくずと下部の返信入口を省略します。タブバーの作成アイコンから返信欄を開けます。補助キーは横スクロールでき、キーボード切替は常に左端に残ります。

## 検証

2026-10-13: Pixel 7 / Android 15 (API 35) の専用エミュレータで確認。ビルド、既存の単体テスト 31 件、下記の描画・画面操作テストが通過しました。透明度 55%・ぼかし OFF を適用して再起動後の復元と、未適用で戻ったときの変更破棄も確認しています。

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest
adb -s emulator-5560 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5560 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5560 shell am instrument -w \
  com.shibaken.androidterminal.test/com.shibaken.androidterminal.CyanVeilSmokeInstrumentation
```

デバイス検証は設定保存・境界値と、透明度 0 / 28 / 100% での端末描画を確認します。文字・ANSI 背景・カーソルの画素も検証します。

専用エミュレータで `-e capture_ui true` を追加すると、ネットワーク接続を行わないテスト用セッションで実際の画面を表示し、タブの確認ダイアログとキーボード切替を検証します。画像はアプリの `files/cyan-veil-captures/` に保存されます。テスト用データは `androidTest` のみに含まれ、テスト終了時に設定・ホスト・セッションを復元します。初回はエミュレータで通知権限を許可してください。

ビルド環境では Android Studio 内蔵 JDK を `JAVA_HOME` に設定しています。実際の SSH / Tailscale サーバーとの再接続や入力の確認は別途必要です。
