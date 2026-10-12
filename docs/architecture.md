# 構成メモ

## 全体像

```
Android アプリ
 ├─ TerminalCanvasView ── Termux TerminalEmulator(main スレッド専用)
 │     ↑ 描画・IME・タッチ→SGR マウス
 ├─ SshTerminalSession ── sshj SSHClient
 │     ├─ shell チャネル(PTY: xterm-256color)…… herdr の TUI がそのまま動く
 │     └─ direct-streamlocal チャネル × N …… ~/.config/herdr/herdr.sock(JSON API)
 ├─ HerdrController ──── session.snapshot / events.subscribe / pane.focus など
 └─ TailscaleManager ─── tsbridge.aar(Go tsnet、リフレクションで呼び出す)
                           Forward(): 127.0.0.1:port → tailnet 上の host:22
```

## パッケージ構成(`app/src/main/java/com/shibaken/androidterminal/`)

| パッケージ | 役割 |
|---|---|
| `data/` | ホストプロファイル・known_hosts(JSON ファイル + StateFlow) |
| `keys/` | 鍵の生成(BouncyCastle)・インポート(sshj)・Keystore AES-GCM による暗号化保存 |
| `ssh/` | 接続・認証(鍵/パスワード/keyboard-interactive)・TOFU のホスト鍵検証・streamlocal チャネル |
| `session/` | `SshTerminalSession`(タブ 1 つ分)、`SessionManager`、UI への問い合わせ(`Prompter`) |
| `terminal/` | 描画ビュー、`TerminalInputConnection`(IME の未確定文字列の処理) |
| `herdr/` | ソケット API クライアント、状態のミラーと各種操作 |
| `tailscale/` | tsbridge のラッパー(ネットワークインターフェース一覧を Go に渡す処理も含む) |
| `service/` | バックグラウンドで接続を維持するフォアグラウンドサービス |
| `ui/` | Compose の各画面 |

## 日本語入力の仕組み

- `onCreateInputConnection` で通常のテキスト欄(`TYPE_CLASS_TEXT`)として振る舞い、IME に変換させる
  - Termux の `TYPE_NULL` では日本語の変換ができない
- `Editable` には変換中の文字列だけを保持し、カーソル位置に下線付きで重ねて描画する
- `commitText` / `finishComposingText` で確定した文字列を送信し、`Editable` を空にしてから `updateSelection(0,0,-1,-1)` を IME に通知する
- 変換中のバックスペースは手元で処理し、変換中でなければ DEL(0x7f)としてリモートに送る
- キーボードを閉じたときは、変換途中の文字列を送らずに破棄する

## herdr 連携の要点(詳細は herdr-api.md)

- herdr は 1 接続につき 1 リクエストしか受け付けないため、RPC のたびに streamlocal チャネルを開く
  - チャネルは同じ SSH 接続の中で多重化されるので負荷は小さい
- ライフサイクル系イベントは「何か変わった」という合図としてだけ使い、120ms のデバウンス後に `session.snapshot` を読み直す
- エージェント状態の変化は、ペインごとに `pane.agent_status_changed` を購読する
  - セッション全体をまとめて購読する手段がないため
  - ペインの構成が変わるたびに購読を張り直す
- 入力待ち(blocked)と完了(done)になったら通知する。ただし画面でそのペインを見ているときは出さない
- 0.8 系は購読開始時に過去のイベントを再送してくるため、開始直後 1.5 秒のイベントは無視する
- 0.9 系では、API からフォーカスを変えると、接続中のすべての TUI クライアントの表示も切り替わる
  - ドロワーでタップすると、ターミナル上の herdr の表示も切り替わるのはこのため

## 既知の制限・今後

- Mosh は未対応
- テキスト選択とコピーは herdr 側の選択機能(長押し→ドラッグ、`copy_on_select`)を使う
- アプリ側で独自にテキストを選択する機能はまだない
- フォントは端末標準の等幅フォント。日本語はシステムのフォールバックで表示される
