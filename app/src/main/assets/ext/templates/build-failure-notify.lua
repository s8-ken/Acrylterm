--[[acrylterm
summary: ビルドやテストの失敗を検知して通知する
usage:
- 画面に BUILD FAILED / npm ERR! / error[E0000] などが出ると通知します
- 同じペインでは 60 秒に 1 回までしか通知しません
]]

-- ペインごとの最終通知時刻(秒)。連続して出る失敗行で通知が溢れないようにする。
local last = {}
local INTERVAL = 60

-- 失敗を示す行のパターン(Java 正規表現)。必要に応じて足してください。
local pattern = "BUILD FAILED|FAILURE:|npm ERR!|error\\[E\\d+\\]|\\bFAILED\\b|\\b[1-9]\\d* failed"

-- term.on_output: 表示中のペインの画面で、変化した行がパターンに合うと呼ばれる。
-- m[0] は一致した行全体。
term.on_output(pattern, function(pane, m)
  local now = os.time()
  if last[pane.id] and now - last[pane.id] < INTERVAL then return end
  last[pane.id] = now
  notify("ビルド/テスト失敗", m[0])
end)
