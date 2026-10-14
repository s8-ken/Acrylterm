# ターミナル拡張(Lua スクリプト)

接続先のエージェント(Claude Code / Codex)やユーザー自身が、アプリ内蔵の Lua(LuaJ 3.0.1、Lua 5.2)で
ターミナルの挙動(出力トリガー・エージェント状態フック・コマンド・補助キー・クイック返信・リモートコマンド実行)を変えられる。
UI の見た目のカスタマイズは対象外。PC 側にインストールするものは無い。

## スクリプトの置き場所
1. **全体スクリプト**: アプリ内 `filesDir/ext/global/*.lua`。アプリ内エディタで編集。
2. **ホストのスクリプト**(ホスト設定で「ホストのスクリプトを読み込む」を on): PC の `~/.config/androidterminal/*.lua` を接続時に取り込み、`filesDir/ext/hosts/<hostId>/` にキャッシュ。

読み込み順は 1 → 2、各ディレクトリ内はファイル名順。ファイル名は英数字・`_`・`-`・`.` のみで `.lua` 終わり。
拡張はホスト設定の「拡張スクリプト」カード内で「このホストで有効にする」(既定 off)が on のホストでだけ動く。

## アプリ内の操作

- ホスト一覧の「拡張スクリプト」から共通スクリプトを作成・編集・削除する。
- エディタの「保存して反映」で保存し、拡張を有効にして接続中のセッションへ再読込する。戻る操作では編集中の変更を破棄する。
- 端末のメニュー →「拡張コマンド」で実行する操作を選ぶ。登録された補助キーは CTRL / ALT の後に並び、横スクロールできる。
- エディタの背景にも外観設定の透明度とぼかしが反映される。コードの文字は薄くならない。

## 設定で有効化
拡張は上級者向けの機能。アプリの「設定」で拡張機能を on にすると、ホスト設定の「拡張スクリプト」カードや端末メニューの拡張項目が現れる。

## ホストで生成
接続先ホストの AI CLI に拡張を書かせる(「ホストのスクリプトを読み込む」が on のホストのみ)。

- ホスト設定の「生成に使う AI コマンド」(`extAgentCommand`、既定 `claude -p`)に、プロンプトを stdin で受け取り結果を stdout に出す CLI を指定する(例: `codex exec -`)。
- 端末メニュー →「拡張を作成」でファイル名とやりたいことを入力して依頼する。既存のホストスクリプト名を選ぶと、その内容を渡して修正を依頼する。
- 流れ: API リファレンス+依頼文を SSH exec でホストの AI コマンドへ渡す → 出力から Lua を取り出す(コードフェンスがあれば除去)→ `~/.config/androidterminal/<name>.lua` に保存 → 再読込。保存は一時ファイル経由で、書きかけは読み込まれない。
- タイムアウトは 5 分。シートを閉じても生成は続き、完了すると通知が出る。失敗時はシートに理由を表示する。

## ホスト別から全体への取り込み
ホスト一覧の「拡張スクリプト」画面で、ホスト別スクリプトを全体スクリプトへ取り込める。同名で内容が同一のホスト側コピーは読み込み時にスキップされる。

## 反映ループ(エージェント向け)
- 接続中は 3 秒ごとに `cksum ~/.config/androidterminal/*.lua` を実行し、変化があれば再読込する(メニューの「拡張を再読込」でも可)。
- 再読込は新しい Lua 環境で全スクリプトを評価し、成功したら登録内容を差し替える。失敗時は直前の状態を維持してエラーを表示する。
- ホストのスクリプトを使うとき、アプリは `~/.config/androidterminal/.app/` に書き戻す:
  - `API.md`: API リファレンス(`app/src/main/assets/ext/API.md` と同内容。エージェントが読む)
  - `log.txt`: 読み込み結果・エラー(`name.lua:行: メッセージ`)・`log()` の出力(末尾 200 行)

## 言語とサンドボックス
- 使えるもの: base、`table`、`string`(`dump` なし)、`math`、`bit32`、`os.time/os.clock/os.date`。`load` は文字列チャンクのみ。
- 使えないもの: `io`、`require`/`package`、`dofile`/`loadfile`、`debug`、`coroutine`、`luajava`、`os` のその他。`getmetatable("")` は nil。
- 暴走対策: 1 回のコールバック(読み込み含む)につき命令数 5,000,000 / 実時間 10 秒(`ssh.exec` 等のブロッキング時間は除く)。超えると以後の全命令でエラーになり、`pcall` では止められない。
- `string.find/match/gmatch/gsub` は対象文字列が 32KB を超えると Lua エラー(`subject too long`)。パターンに `-` か `*` を含む場合は 16KB まで。ネイティブ実行で命令数制限が効かないための措置。
- 文字列は `..`・`string.rep`・`string.format`・`table.concat` のどれでも 1MB まで(`string too long`)。`string.format` の幅・精度は 1000 まで。
- メモリ: ヒープ使用量が最大ヒープの約 60% を超えると `memory limit exceeded` で中断する(ベストエフォート)。`string.rep` は結果 1MB まで。
- 正規表現(`term.on_output`/`quick_reply.rule`)の照合には時間制限があり(出力 1 行 50ms、クイック返信 200ms)、超えたら不一致扱いで `log` に 1 度だけ記録する。照合は Lua スレッド/バックグラウンドで行いメイン UI を塞がない。
- `string` ライブラリはランタイム(セッション)ごとに独立。`string.upper = ...` は他セッションに影響しない。
- 信頼の単位はホスト。スクリプトはそのホストのセッション内でだけ動き、`ssh.exec` もそのホストにしか届かない。SSH 鍵や他ホストには触れない。
- 正規表現は **Java/Kotlin の `Regex` 構文**(Lua パターンではない)。部分一致(find)。不正な正規表現は登録時に Lua エラー。

## API リファレンス
```lua
term.on_output(regex, function(pane, m) end)   -- m[0]=行全体, m[1..]=グループ(未参加は "")
on("agent_status", function(pane, status, prev) end) -- idle/working/blocked/done/unknown
on("connected", function() end)
command(name, function(ctx) end)               -- ctx.pane = フォーカス中のペイン(無ければ nil)。同名は後勝ち
keys.add(label, "text" | {send=..., keys={...}} | function(pane) end)
quick_reply.rule(regex, { "Yes", {label="No", send="n\r"}, {label="Esc", keys={"esc"}} })
notify(title, body)        -- Android 通知(タップでフォーカス中のペインへ)
toast(text)
log(...)                   -- tostring をタブ連結。print も同じ
ssh.exec(cmd [, timeoutSec=15])  -- stdout を返す。失敗は Lua エラー
timer.after(sec, fn) -> id       -- sec 秒後に 1 回(下限 0.1 秒、上限 24 時間)
timer.every(sec, fn) -> id       -- 実行完了から sec 秒間隔で繰り返し(下限 1 秒)。5 回連続エラーで自動停止
timer.cancel(id) -> boolean      -- 取り消し(自身のコールバック内でも可)。タイマーは最大 32 個、再読込で全停止(読込失敗時は旧スクリプトのタイマーを維持)
panes()                    -- 全ペインの配列
```
- **ペインオブジェクト**: `id` / `title` / `agent`(nil 可) / `status` / `cwd`(nil 可)。メソッドは `:` で呼ぶ: `pane:send(text)`(生テキスト。Enter は `"\r"`)、`pane:send_keys({"enter"})`(herdr のキー名)、`pane:read(n)`(末尾 n 行、既定 40)。SHELL モードでは単一のペイン `id="shell"`。
- **keys.add**: 文字列はそのままテキスト送信。テーブルは `send` を送ってから `keys` を送る。関数は押下時に対象ペインを渡して呼ぶ。
- **quick_reply.rule**: ペイン内容が regex に一致したら(複数行モード、先頭の一致ルールが勝つ)既定の候補の代わりにこの選択肢を出す。文字列の選択肢は「ラベル = 送信文字列、送信時に Enter(`\r`)を付ける」。テーブルは `label`(省略時は `send` または最初の `keys`)、`send`(そのまま送信)、`keys`。

### 例
```lua
term.on_output("BUILD (\\w+)", function(pane, m)
  if m[1] == "FAILED" then notify("Build failed", pane.title) end
end)
on("agent_status", function(pane, status)
  if status == "blocked" then notify(pane.title, "needs input") end
end)
keys.add("gst", "git status\r")
command("uptime", function(ctx) toast(ssh.exec("uptime")) end)
```

## 制限
- `term.on_output` は画面差分(行 index ごとに前回スナップショットと比較し、変化した非空行のみ)で動く。画面外で detach 中のペインは出力を読まないのでトリガーも動かない。`agent_status` は herdr のイベントなので全ペインで動く。
- トリガーが 1 つも無ければ監視コストはゼロ。
- 画面に同じ文字列が残り続けても再発火しない。

## Kotlin 側の構成(`ext/`)
`ExtHost`(セッションが実装)/ `ExtEngine`(窓口)/ `LuaRuntime`(サンドボックス・専用スレッド)/ `ExtApi`(Lua バインディング)/ `ExtRegistry`(登録内容の公開型)/ `OutputWatcher`(画面差分)/ `ScriptStore`(ファイル保管)。
