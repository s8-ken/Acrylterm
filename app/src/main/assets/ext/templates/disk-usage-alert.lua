--[[acrylterm
summary: ホームのディスク使用率が 90% 以上になったら通知する
usage:
- 10 分ごとにホストの df を確認します
- 一度通知したら、使用率が下がるまで再通知しません
- ホストのファイルは変更しません(読み取りのみ)
]]

local THRESHOLD = 90
local alerted = false  -- 通知済みか。しきい値を下回ったら解除する。

-- timer.every(秒, 関数): 前回の実行が終わってから指定秒後に繰り返し呼ばれる。
timer.every(600, function()
  local ok, out = pcall(ssh.exec, "df -P ~")
  if not ok then return end  -- 取得できなければ今回は見送る
  -- df -P の最終行から「NN%」を取り出す。形式が想定外なら何もしない。
  local pct = tonumber(tostring(out):match("(%d+)%%[^%%]*$"))
  if not pct then
    log("disk-usage-alert: 解析できませんでした")
    return
  end
  if pct >= THRESHOLD then
    if not alerted then
      alerted = true
      notify("ディスク使用率が高い", "ホームの使用率 " .. pct .. "%")
    end
  else
    alerted = false
  end
end)
