--[[acrylterm
summary: y/n の確認プロンプトに「y」「n」の返信候補を出す
usage:
- [y/N] / [Y/n] / (y/n) のような確認が画面に出ると、返信候補に y と n が並びます
- 選ぶとその文字を入力して Enter まで押します
]]

-- quick_reply.rule(正規表現, 選択肢の一覧)
-- 画面のテキストが正規表現に合うと、既定の候補の代わりにこの選択肢が出る。
-- 文字列の選択肢は、ラベル兼入力文字列で、入力後に Enter が押される。
local choices = { "y", "n" }

quick_reply.rule("\\[[yY]/[nN]\\]", choices)   -- [y/N] [Y/n] [y/n]
quick_reply.rule("\\([yY]/[nN]\\)", choices)   -- (y/n) (Y/N)
