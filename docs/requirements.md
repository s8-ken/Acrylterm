# androidTerminal 要件・設計メモ(ドラフト v0.1)

最終更新: 2026-10-12

## 目的
自宅の Mac mini へ Tailscale 経由で SSH 接続するための Android ターミナルアプリ。
Termius の致命的な不満点である「日本語入力ができない」を解消する。

## 主な利用シーン
- Android 端末 → (Tailscale) → 自宅 Mac mini に SSH
- Mac mini 上の herdr で複数ペイン/複数 AI エージェントを操作する

## 要件

### 必須 (MVP)
1. **SSH クライアント**:パスワード認証・公開鍵認証、ホストキー検証(known_hosts 管理)
2. **日本語入力 (IME) 対応**:Gboard 等で変換中の文字(未確定文字列)を表示し、確定した文字列を UTF-8 で送る
3. **SSH キー管理**
   - Ed25519 / ECDSA / RSA の鍵ペア生成
   - 既存の秘密鍵(OpenSSH 形式、パスフレーズ付きも含む)のインポート
   - 公開鍵のコピー・共有(authorized_keys に貼る用)
   - 秘密鍵は Android Keystore で守った鍵で暗号化して保存し、生体認証でのロック解除はオプション
   - ホストごとに使う鍵を割り当てる
4. **Tailscale 内蔵**:`tsnet`(Go)を組み込み、アプリ単体で tailnet に参加する
   - VPN 枠を使わないユーザースペース実装なので、公式 Tailscale アプリや他の VPN と共存できる
   - MagicDNS 名(例: `mac-mini`)で接続先を指定できる
   - 内蔵 Tailscale を使うか、端末側のネットワークを使うかを接続ごとに切り替えられる
5. **モバイル向け補助キーバー**:Esc / Tab / Ctrl / Alt / 矢印 / `|` `~` `/` など

### 強く希望 (herdr 連携)
herdr を前提にした設計にしてもよい(ユーザー希望)。

### 将来 / オプション
- Mosh(UDP を使うため、tsnet 経由での実装は要検証)
- 複数ホストのプロファイル管理、ポートフォワード
- テーマ・フォント設定

## herdr について(調査メモ)
- Rust 製のターミナルマルチプレクサで、AI コーディングエージェントの状態(作業中/待機/ブロック)をサイドバーに表示する。Apache 2.0。
- サーバー/クライアント型で、セッションはバックグラウンドのサーバーが保持する(デタッチ/再接続可)。
- デフォルトのプレフィックスは `Ctrl+b`(`v`/`-` で分割、`w` でワークスペース切替、`q` でデタッチ など)。マウス操作にも対応。
- **ソケット API**:`~/.config/herdr/herdr.sock` に改行区切り JSON を送る。
  - 例: `{"id":"req_1","method":"ping","params":{}}`
  - `workspace.list` / `tab.list` / `pane.list` / `pane.split` / `pane.focus_direction` / `pane.resize` / `pane.send_text` / `pane.read` / `session.snapshot` など
  - `events.subscribe` で、ペイン作成やエージェント状態変化(`pane.agent_status_changed`)などのイベントをプッシュで受け取れる
- 参考: https://herdr.dev/docs/socket-api/ , https://herdr.dev/docs/connecting-machines/

## herdr 連携の設計案(ハイブリッド方式)
- **画面描画**:SSH の PTY 上で herdr の TUI をそのまま表示する(ターミナルとして素直に動かす)。
- **ネイティブ操作レイヤー**:同じ SSH 接続上で、OpenSSH の Unix ソケット転送(`direct-streamlocal@openssh.com`)を使って
  リモートの `herdr.sock` に直接つなぐ。これにより、次のことを Android ネイティブ UI で実現する。
  - ドロワーにワークスペース/タブ/ペインとエージェント状態を一覧表示し、タップで切り替える
  - ボタンやスワイプで分割・ズーム・ペイン移動
  - エージェントが blocked になったら Android 通知を出す(`events.subscribe`)
  - 狭い画面では herdr のサイドバーを隠し、ネイティブのドロワーで代替する
- ソケット転送ができない環境では、`ssh exec` で `herdr ... `(CLI、JSON 出力)を呼ぶ方式にフォールバックする。
- タッチ操作は SGR マウスイベントに変換し、タップでのペインフォーカスや境界ドラッグでのリサイズにも対応する。

## 技術スタック(案)
| 領域 | 候補 |
|---|---|
| 言語/UI | Kotlin + Jetpack Compose |
| ターミナルエミュレータ | Termux の `terminal-emulator` / `terminal-view`(Apache 2.0)を流用し、IME 入力部分は自作 |
| SSH | sshj または Apache MINA SSHD(Unix ソケット転送への対応状況を要確認) |
| Tailscale | `tsnet` を gomobile で AAR 化して組み込む |
| 鍵の保護 | Android Keystore + EncryptedFile / BiometricPrompt |

## 未決事項
- 最小対応 Android バージョン
- herdr 連携を MVP に含めるか、フェーズ 2 にするか
- SSH ライブラリの選定(Unix ソケット転送への対応を検証してから決める)
