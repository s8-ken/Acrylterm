--[[acrylterm
summary: git status / diff / log をワンタップで実行するマクロ
usage:
- ミニキー列に「gst」「gdf」「glg」のマクロが並びます
- タップすると、フォーカス中のペインにコマンドを入力して Enter まで押します
]]

-- keys.add(ラベル, {send = 入力する文字列, keys = 続けて押すキー})
-- 文字列だけを渡すと貼り付けのみで Enter は押されないので、実行したいときは
-- このように keys = {"enter"} を付ける。
keys.add("gst", {send = "git status", keys = {"enter"}})
keys.add("gdf", {send = "git diff", keys = {"enter"}})
keys.add("glg", {send = "git log --oneline -20", keys = {"enter"}})
