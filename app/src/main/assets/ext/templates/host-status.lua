--[[acrylterm
summary: ホストの稼働時間とホームのディスク使用量を表示する
usage:
- 拡張コマンドの「ホストの状態」を実行すると、結果がトーストで表示されます
- ホストのファイルは変更しません(読み取りのみ)
]]

-- command(名前, 関数): ミニキー列にマクロとして並ぶコマンドを登録する。
command("ホストの状態", function(ctx)
  -- ssh.exec はこのホストでコマンドを実行し、標準出力を返す。失敗は Lua エラーになるので pcall で受ける。
  local ok, up = pcall(ssh.exec, "uptime")
  local ok2, df = pcall(ssh.exec, "df -hP ~ | tail -n 1")
  if not ok or not ok2 then
    toast("取得に失敗しました")
    return
  end
  toast((up:gsub("%s+$", "")) .. "\n" .. (df:gsub("%s+$", "")))
end)
