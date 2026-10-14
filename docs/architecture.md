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

## 画面構成(PANES モード)

- `TerminalScreen` は、PANES モードではペイン表示を `ui/PaneLayout.kt` に任せる。SHELL / LEGACY_TUI は従来どおり単一の `TerminalCanvasView`
  - 上から: 上部バー(戻る・ホスト名・メニュー)/ ペイン領域 / タブバー(☰・タブ・一覧・作成欄・+)/ 補助キー
- `PaneLayout`: フォーカス中のタブの `TabLayout.normalizedPaneRects()` の比率で、ペインごとの `TerminalCanvasView` を並べる
  - View は `PaneViewPool` がペイン ID ごとに 1 つ保持し、再コンポーズやタブ切替で作り直さない。文字サイズ・直接入力・Ctrl/Alt(`Modifiers`)は全ペイン共通
  - 画面に出ているペイン ID を `setVisiblePanes` に渡し、出ているペインだけ attach する
  - 1 ペインが 40 桁未満になる配置、ズーム中、レイアウト未取得のときは、フォーカス中のペインだけを全面表示する(`PaneLogic.needsSingleMode`)
  - フォーカス中のペインの View だけが focusable で、IME と補助キーはそこへ向かう
- ジェスチャーは `TerminalCanvasView` が判定し、コールバックで `PaneLayout` に渡す(`paneMode = true` のとき)
  - タップ: フォーカス外のペインならフォーカス移動、フォーカス中ならマウス有効時にクリック送信
  - ダブルタップ: 1 回目のタップはクリックとして即時に送り、2 回目だけをズーム切替にする
  - 長押し: 放射状メニュー(`RadialMenu.kt`)。指は押したままで、View のタッチ列がそのままメニューの選択に使われる
  - 2 本指の水平スワイプ: 前 / 次のタブ。ピンチとは「重心の水平移動量」と「指の間隔の変化」で区別する(`PaneGestureMath`)
  - 区切り線: 24dp の透明なドラッグ領域。ドラッグ中はレイアウトを即時に更新し、指を離したときに `layout.set_split_ratio`(`tab_id` + 根からの `path`)で確定する。`path` は分割の矩形の包含関係から求める(`PaneLogic.splitPath`)
- 入力待ちのペイン: ペイン下端に `QuickReplyBar`(`pane.read` の末尾 15 行から選択肢を推定。`QuickReplies.parse`)。状態が変わると消える
- 作成欄: `ComposerPanel`(IME 直上に固定するインライン入力パネル。ボトムシートは使わない)。下書きはペインごとにメモリ上で保持し、送信は `pane.send_input`(テキスト + Enter)
- 一覧: `PaneOverview`。全ワークスペースの全タブを、`TabLayout` の矩形を縮小したカードで表示する(エージェント状態を色と形で示す)
- ユーザー向けの文言に「herdr」は出さない(通知・ドロワー・ホスト設定を含む)

## 拡張(Lua)

詳細は `docs/extensions.md`。ホスト設定の「拡張スクリプト」が on のセッションだけ有効(既定 off)。

- `ext/`: LuaJ ランタイム(`ExtEngine`、専用 1 スレッドで直列化)、API(`ExtApi`)、登録内容(`ExtRegistry`)、`ScriptStore`(`filesDir/ext/`)、`OutputWatcher`(画面差分)、`ExtSync`(ホスト取り込み用コマンドの生成・パース)
- `session/SessionExt.kt`: セッション 1 つにつき 1 つ。`ExtHost` をセッションに対して実装し(PANES は `HerdrController`、SHELL は単一ペイン `shell`)、接続のたびにスクリプトを再読込して `connected` を発火する
- ホストのスクリプト(`extLoadFromHost`): 接続時に `sh -c` で `~/.config/acrylterm/*.lua` を 1 回の exec で取得して(旧 `~/.config/androidterminal/` だけがあれば先に移す) `filesDir/ext/hosts/<id>/` にキャッシュ。接続中は 3 秒ごとの `cksum` で変更検知→再取得→再読込。`.app/API.md`・`.app/log.txt` へ base64 経由で書き戻す(親ディレクトリが無ければ作らない)
- 出力トリガー: トリガー登録があるときだけ各 `PaneTerminal.screenRevision`(200ms デバウンス)から表示行を読み、変化行を `OutputWatcher` → `ExtEngine.dispatchOutput`
- エージェント状態: `HerdrController.onAgentTransition`(全遷移)→ `dispatchAgentStatus`。既存の通知(`AgentAlertListener`)とは独立
- UI: `ExtraKeysBar` のミニキー列末尾に `ExtRegistry.keys` と `commands`(⚡ マクロ)と「＋」、`QuickReplyBar` は `matchQuickReply` を優先、`TerminalScreen` のメニューに「拡張一覧」「拡張を作る」「拡張の再読込」(マクロ = keys+commands はミニキー列の ⚡ チップ)、ホスト一覧のメニューから `ExtScriptsScreen`(全体スクリプトの編集。保存で稼働中セッションを再読込)
- 通知は `SessionManager.notify`(エージェント通知と同じチャンネル・タップでペインを開く)

## 既知の制限・今後

- Mosh は未対応
- テキスト選択とコピーは herdr 側の選択機能(長押し→ドラッグ、`copy_on_select`)を使う
- アプリ側で独自にテキストを選択する機能はまだない
- フォントは端末標準の等幅フォント。日本語はシステムのフォールバックで表示される
