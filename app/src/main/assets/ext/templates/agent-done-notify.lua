--[[acrylterm
summary: エージェントの作業が完了したら通知する
usage:
- エージェントのペインが「完了(done)」になると Android 通知が出ます
- 通知をタップするとアプリに戻ります
]]

-- on("agent_status", ...) は、エージェントの状態が変わるたびに呼ばれる。
-- status: idle | working | blocked | done | unknown
on("agent_status", function(pane, status, prev)
  if status == "done" then
    -- 本文はペインのタイトル。空ならペイン ID で代用する。
    local body = pane.title
    if body == nil or body == "" then body = pane.id end
    notify("エージェント完了", body)
  end
end)
