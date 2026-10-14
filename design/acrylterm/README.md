# Acrylterm icon

正式表記は **Acrylterm**、読みは **アクリルターム**(旧名 Veildeck)。

Cyan Veil UI の黒緑の背景、シアンとミントの光、丸みのあるパネルを重ねたアイコン。前面の `>_` はターミナル、背面のパネルは複数の作業環境を表す。

- [Figma 原稿](https://www.figma.com/design/8FFBaAZ3WRtuGVb236MBBy?node-id=2-2)
- [Figma 表示サイズ・形状プレビュー](https://www.figma.com/design/8FFBaAZ3WRtuGVb236MBBy?node-id=2-25)
- `icon.svg` / `icon.png`: 512 × 512 の正方形アイコン。SVG は編集可能なベクター原稿、PNG は Figma からの書き出し。
- `foreground.svg`: Android 用の透明な前景。108 × 108 の座標系。
- `monochrome.svg`: テーマ対応アイコン用の単色前景。
- `preview.png`: 角丸・丸形と 64 / 48 / 32 px の表示確認。

Android では `app/src/main/res/drawable/ic_launcher_foreground.xml` と `ic_launcher_backplate.xml` を重ねる。テーマ対応は `ic_launcher_monochrome.xml`、通知は余白を詰めた `ic_notification.xml` を使う。SVG と VectorDrawable は同じ座標・色で管理する。

前景は [Android の Adaptive icons ガイド](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive) に合わせて 108 × 108 dp で作成。正方形書き出しと形状プレビューは中央 72 × 72 の表示領域を切り出している。

UI の配色参照は `8e0be34` の `CyanVeil` テーマ。アプリ ID、鍵の保存先、拡張スクリプトのディレクトリは既存データとの互換性のため従来の識別子を使用する。
