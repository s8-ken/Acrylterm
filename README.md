# androidTerminal

自宅の Mac mini に Tailscale 経由で SSH 接続するための Android ターミナルアプリ。

- **日本語入力**:IME の未確定文字列(変換中の文字)をカーソル位置に表示し、確定した文字列だけを送信する
- **herdr 連携**:herdr のソケット API を SSH 経由で直接操作する
  - ドロワーにワークスペース・タブ・ペインとエージェント状態を表示し、タップで切り替え
  - 分割・ズーム・ペインを閉じる操作
  - ペイン内容のプレビューと返信
  - エージェントが入力待ちになったら Android 通知
- **SSH 鍵管理**:Ed25519 / RSA の生成、既存鍵のインポート、公開鍵のコピー・共有
  - 秘密鍵は Android Keystore で暗号化して保存
  - 使用時に生体認証を要求する設定も可能
- **Tailscale 内蔵**:`tsnet` をアプリに組み込む(VPN 枠を使わないので公式アプリと併用可)。後から追加できるオプションで、なくてもビルドできる

設計と調査メモは `docs/` にあります。

- `docs/requirements.md`:要件
- `docs/architecture.md`:構成
- `docs/herdr-api.md`:herdr ソケット API のリファレンス

## ビルド(Mac / Android Studio)

1. Android Studio でこのフォルダを開く(Gradle 同期で依存関係が自動取得されます)
2. 実機をつなぎ、`app` を Run
   - コマンドラインの場合は `./gradlew :app:installDebug`

この時点で、端末側の Tailscale アプリ(VPN)を使えば接続できます。その場合はホスト設定で「Tailscale 経由」をオフにしてください。

### Tailscale を内蔵する(任意)

```sh
# 事前に Android Studio > SDK Manager > SDK Tools で「NDK (Side by side)」をインストール
./tsbridge/build.sh        # app/libs/tsbridge.aar を生成
```

生成後にアプリを再ビルドすると、Tailscale 画面からログインできるようになります。初回は gomobile と tailscale.com の取得のため数分かかります。

## Mac 側の準備

- **リモートログイン**:システム設定 > 一般 > 共有 > 「リモートログイン」をオン
- **公開鍵の登録**:アプリの「SSH 鍵」画面で鍵を生成し、公開鍵をコピーして Mac の `~/.ssh/authorized_keys` に追記する
- **herdr**:0.9 系を推奨(0.8 系でも動作します)
  - herdr のサーバーが起動していなくても、ログイン後に `herdr` を自動実行するので立ち上がります
  - 実行するコマンドはホスト設定で変更できます
- **sshd の設定**:`AllowStreamLocalForwarding` を `no` にしていないこと(デフォルトは yes)

## 操作

| 操作 | herdr 上(マウス有効時) | 通常シェル |
|---|---|---|
| タップ | クリック(ペインのフォーカス・タブ切替など) | キーボード表示 |
| 縦ドラッグ | ホイールスクロール | スクロールバック |
| 長押し→ドラッグ | 左ボタンドラッグ(境界のリサイズ・選択) | ― |
| ピンチ | 文字サイズ変更 | 文字サイズ変更 |

- 補助キーバーの `^B` は herdr のプレフィックス(Ctrl+b)です
- CTRL / ALT はタップすると次の 1 キーにだけ効きます
- メニューの「直接入力(変換なし)」は、コマンド入力時に予測変換を止めたいときに使います

## ライセンス表記

`terminal-emulator/` は Termux の terminal-emulator と terminal-view の一部(Apache License 2.0)を取り込んだものです。詳細は `terminal-emulator/NOTICE` を参照してください。
