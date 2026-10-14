# Veildeck extension API (Lua)

You (an AI coding agent) can customize the Veildeck Android app by writing Lua files into
`~/.config/androidterminal/*.lua` on this machine. No install is needed on the PC side.

Host script sync/generation currently requires a POSIX shell and utilities (`sh`, `cksum`, `cat`, `base64`, etc.). Native Windows PowerShell-only hosts are not supported by that workflow; a POSIX environment such as a WSL SSH endpoint is needed. The input methods below do not switch newline conventions based on OS.

## Reload loop
1. Edit or create `~/.config/androidterminal/<name>.lua` (name: letters, digits, `_`, `-`, `.`; ends with `.lua`).
2. The app polls this directory about every 3 seconds (`cksum`) and reloads when something changed.
3. Check `~/.config/androidterminal/.app/log.txt` for the result: load errors (`name.lua:LINE: message`), uncaught callback errors and everything you `log(...)`. The last 200 lines are kept.
4. If a reload fails, the app keeps the previously working scripts. Fix the error and save again.

Files are loaded in file-name order (after the user's in-app global scripts). Do not touch `.app/` except for reading.

## Header comment (recommended)
Begin each script with a header so the app can show what it does:
```
--[[veildeck
summary: <one line: what it does>
usage:
- <how to use, one bullet per line>
]]
```
Write it in the user's language and keep it updated when you change the script.

## Language
Lua 5.2 (LuaJ). Available: base functions, `table`, `string`, `math`, `bit32`, and `os.time/os.clock/os.date`.
NOT available: `io`, `require`/`package`, `dofile`/`loadfile`, `debug`, `coroutine`, `os.execute` and the rest of `os`. `load` accepts strings only.
Each callback is limited to ~5M VM instructions / 10 s of Lua time; `while true do end` is aborted with an error.
`string.find/match/gmatch/gsub` reject subjects longer than 32KB (16KB if the pattern contains `-` or `*`) with "subject too long"; any string built by `..`, `string.rep`, `string.format`, `table.concat` is capped at 1MB ("string too long"), `string.format` width/precision at 1000; a script is aborted ("memory limit exceeded") if the app heap gets nearly full.
Regex matching has a time limit (50 ms per output line, 200 ms for quick replies); a pattern that times out is treated as "no match" and noted once in the log, so avoid nested quantifiers like `(a+)+`.
Regexes below are **Java regex** syntax (not Lua patterns), matched with "find" (substring) semantics.

## API
```lua
-- Output triggers. Runs when a changed line on the visible screen matches the regex.
-- m[0] = whole line, m[1..] = capture groups.
term.on_output("BUILD (\\w+)", function(pane, m)
  if m[1] == "FAILED" then notify("Build failed", m[0]) end
end)

-- Events
on("agent_status", function(pane, status, prev) end) -- status: idle|working|blocked|done|unknown
on("connected", function() toast("connected") end)

-- Commands (shown in the app's "extension commands" sheet)
command("git status", function(ctx)
  if ctx.pane then ctx.pane:submit("git status") end -- ctx.pane = focused pane or nil
end)

-- Extra keys (buttons next to the built-in key bar)
keys.add("gst", {send = "git status", keys = {"enter"}}) -- paste + Enter in one operation
keys.add("ESC2", {send = "", keys = {"esc", "esc"}})    -- table: text then herdr key names
keys.add("clr", function(pane) pane:submit("clear") end)

-- Quick replies. If the pane text matches the regex, these choices replace the default ones.
-- A plain string is both the label and the pasted text, followed by an explicit Enter key.
-- A table: {label=, send=, keys=}; paste `send` and press `keys` in one operation.
quick_reply.rule("Proceed\\? \\[y/n\\]", { "y", {label = "No", send = "n", keys = {"enter"}}, {label = "Esc", keys = {"esc"}} })

notify("title", "body")        -- Android notification (tap opens the focused pane)
toast("short message")
log("anything", 1, {})         -- tostring()-joined with tabs, goes to .app/log.txt
local out = ssh.exec("uname -a", 15) -- run on THIS host, returns stdout; errors raise Lua errors
local list = panes()               -- all panes

-- Timers (callbacks run on the extension thread like any other callback)
local id = timer.after(5, function() toast("5s passed") end) -- once; min 0.1 s
local tid = timer.every(60, function() log(ssh.exec("uptime")) end) -- repeating; min 1 s
timer.cancel(tid)                  -- true if it existed; also fine inside its own callback
```

### Pane objects
Fields: `id`, `title`, `agent` (e.g. "claude", may be nil), `status` (`idle|working|blocked|done|unknown`), `cwd` (may be nil).
Methods (use `:`):

| Method | Meaning |
| --- | --- |
| `pane:send(text)` | Paste text without adding Enter. |
| `pane:submit(text)` | Paste text, then press Enter, in one operation. Use this to run a command or submit a prompt. |
| `pane:send_keys({"enter","esc"})` | Press named keys in list order. |
| `pane:send_raw(text)` | Send the UTF-8 string unchanged, including control characters; no paste wrapper or newline conversion. Advanced use only. |
| `pane:read(n)` | Last n screen lines, default 40. |

In plain shell mode there is a single pane with `id = "shell"`.

```lua
pane:send("draft")                  -- input only
pane:submit("echo hello")           -- input + Enter
pane:send_keys({"enter"})           -- confirm the current input
pane:send_raw("\003")               -- literal Ctrl+C byte
```

`send` and `submit` use terminal paste semantics, consistently in PANES and SHELL modes:
- LF (`\n`), CRLF (`\r\n`) and CR (`\r`) are normalized to CR for terminal input, regardless of whether the host is macOS, Linux or Windows. File newline conventions do not determine the Enter key encoding. ESC and C1 control characters are removed, as with clipboard paste; use `send_raw` for those characters.
- When the receiving program enables bracketed paste, embedded newlines remain pasted content. `submit` adds exactly one Enter **outside** the paste wrapper, even for an empty string. Without bracketed paste, the receiving program may interpret embedded newlines as Enter. The API cannot guarantee multiline insertion for such programs.
- These methods operate on the current program and input buffer. `submit` does not clear existing input, choose a shell or wait for command completion.
- Calls wait for transport acceptance and preserve Lua call order. PANES input from the app's RPC queue is ordered per pane; a submission's text and keys share one RPC. SHELL submits one ordered stream write. This does not lock out another client or guarantee the target program has processed the input.
- Disconnection, invalid keys and rejected writes raise Lua errors (`pcall` can catch them). An acknowledgement timeout may mean input was already sent: do not retry blindly. Uncaught callback errors go to the extension log and toast.

Migration: replace `pane:send("command\r")` with `pane:submit("command")`. For a key button, use `{send="command", keys={"enter"}}` or a callback calling `submit`. A string passed to `keys.add` only pastes; a string in `quick_reply.rule` submits. Use `send_raw` only when literal terminal control characters are intended.

## Limits
- `term.on_output` only sees panes that are currently being displayed (detached/hidden panes are not read). `agent_status` works for all panes.
- Triggers fire on screen *changes* per row, not on raw byte streams; the same text staying on screen does not re-fire.
- `ssh.exec` blocks the extension thread until it returns (default timeout 15 s). Scripts run only for the host they came from.
- Timers: `sec` is clamped to 0.1..86400 (`after`) / 1..86400 (`every`); at most 32 live timers ("too many timers"). `every` waits `sec` after each run finishes (no overlap), keeps running after a callback error, and is cancelled after 5 consecutive errors (logged). All timers are cancelled on reload; a failed reload keeps the old scripts and their timers.

## Example: notify when an agent needs input
```lua
on("agent_status", function(pane, status, prev)
  if status == "blocked" then
    notify(pane.title, "needs input")
    log("blocked", pane.id, pane.agent)
  end
end)
```
