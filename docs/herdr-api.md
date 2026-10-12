# herdr socket API: reference for the Android client

Sources: release tags `v0.8.2` (9eb5214) and `v0.9.3` (7b116c0), checked out as git worktrees at
local worktrees. The original clone is
**post-0.9.3 main** (dc6b6e5), but its `src/api` differs from v0.9.3 only in internal shutdown plumbing.
Paths below are relative to the repo root. Where 0.8.2 and 0.9.3 behave the same, the 0.9.3 path is cited.
**[?]** marks a claim I could not fully confirm.

---

## 1. Transport

### Socket path
`src/session.rs` (`active_api_socket_path`, `api_socket_path_for`, `data_dir_for`) and `src/config/io.rs` (`config_dir`):

1. `--session <name>` given explicitly → `<config_dir>/sessions/<name>/herdr.sock`
2. else, if `$HERDR_SOCKET_PATH` is set, use that path
3. else, if `$HERDR_SESSION=<name>` is set and is not the default → `<config_dir>/sessions/<name>/herdr.sock`
4. else → `<config_dir>/herdr.sock`

`config_dir` is `$XDG_CONFIG_HOME/herdr` if that variable is set, otherwise `$HOME/.config/herdr`. **This applies on macOS too:
the path is not under `~/Library`.** A debug build uses `herdr-dev` in place of `herdr`.

For `direct-streamlocal@openssh.com`, send an **absolute** path. sshd does not expand `~`. Get `$HOME` first, for
example with an `exec` channel running `echo $HOME`, or run `herdr session list --json`.

### Framing and connection model (important)
`src/api/server.rs` (`handle_connection_with_stop`, `read_initial_request_line_with_limits`, `stream_subscriptions`):

- The protocol is UTF-8 newline-delimited JSON, one object per line, in both directions.
- **Each connection carries exactly one request.** The server reads **one** line, writes one response line, and the
  connection ends. Pipelining and multiplexing on one connection are not supported.
  - The first line must arrive within **5 s** (`INITIAL_REQUEST_TIMEOUT`) and must not exceed **1 MiB**. Otherwise the
    connection is closed with no response.
  - `events.subscribe` is the only exception: after the first response line, the server keeps the connection open and
    streams event lines until either side closes it. `events.wait`, `agent.wait`, `agent.prompt` with `wait`, and
    `pane.wait_for_output` hold the connection until they produce a single response.
- **What this means for Android:** open a new `direct-streamlocal` channel for every RPC. Channels are multiplexed over
  the one SSH session, so they are cheap. Keep one long-lived channel for the subscription. Several concurrent RPCs
  means several concurrent channels. Each connection runs on its own server thread
  (`spawn_connection_handler`), and all requests are serialized through the app's single request queue.
- The server writes with a 5 s send timeout. Subscription streams are polled every **100 ms**
  (`CONNECTION_POLL_INTERVAL`), so event latency is up to about 100 ms.

### Envelopes
`src/api/schema.rs` (`Request`, `Method`) and `src/api/schema/response.rs`:
```json
→ {"id":"r1","method":"workspace.list","params":{}}
← {"id":"r1","result":{"type":"workspace_list","workspaces":[...]}}
← {"id":"r1","error":{"code":"workspace_not_found","message":"..."}}
```
- `id` is a **string** chosen by the client. `params` is required, even when it is `{}`.
- `result.type` is the response variant in snake_case. Dispatch on it.
- If a line fails to decode (bad JSON, unknown method, or bad params), the reply is
  `error.code:"invalid_request"` with message `"invalid request: <serde msg>"`. The `id` is echoed if it can be
  recovered, otherwise it is `""`. **An unknown method on an older server also gives `invalid_request`**, not a
  dedicated code. The only exception is the retired `pane.graphics.*` methods, which return `unknown_method`.
- Error codes you will see: `invalid_request`, `invalid_params`, `workspace_not_found`, `tab_not_found`,
  `pane_not_found`, `invalid_key`, `pane_send_failed`, `pane_layout_unavailable`, `server_unavailable` (app channel
  closed, app timeout, or shutting down), `events_lost`, `timeout`, `workspace_group_close_required`,
  `confirmation_required`, `invalid_regex`, `invalid_ratio`.
- Clients should ignore unknown fields. Many optional fields are omitted when empty rather than sent as `null`
  (`skip_serializing_if`).

### Handshake and version
There is no handshake. Use `ping`, which the connection thread answers directly without going through the app
(`server.rs` `handle_request`):
```json
→ {"id":"p","method":"ping","params":{}}
← {"id":"p","result":{"type":"pong","version":"0.9.3","protocol":22,
     "capabilities":{"live_handoff":true,"detached_server_daemon":true,
       "endpoint_protocol_generation":1,"surface_interest":true,"health_check":true,
       "ssh_agent_registration":true}}}
```
- `protocol` is the internal binary wire version (`src/protocol/wire.rs` `PROTOCOL_VERSION`): **0.8.0 = 19,
  0.8.2 = 20, 0.9.3 = 22**. The JSON API itself has no version number. Feature-detect with `version`, and treat an
  `invalid_request` reply to a method as "unsupported".
- `capabilities` in 0.8.2 has only `live_handoff` and `detached_server_daemon`.
- `session.snapshot` also returns `version` and `protocol`.

### Auth and permissions
- There is no token or authentication handshake. Access control is the filesystem only: the socket is `chmod 0600`
  right after bind (`SOCKET_PERMISSION_MODE`, `src/ipc.rs` `restrict_socket_permissions`), and the parent directory
  is created with default permissions. **Anyone who can connect as the owning user gets full control**, including
  `server.stop`, `pane.send_text`, and `plugin.*`.
- A streamlocal forward from sshd runs as the logged-in user, so it passes the permission check. The sshd setting
  `AllowStreamLocalForwarding` must not be `no` (the OpenSSH default is `yes`).
- The peer identity is only logged, for `server.stop`.

---

## 2. Methods the client needs

IDs are opaque strings: workspace `w1`, tab `w1:t1`, pane `w1:p3`. The suffix is a custom base-N encoding
(`src/workspace.rs` `encode_public_number`). **Pane and tab numbers are stable for the life of the object; they are
not renumbered.** A `pane.move` to another workspace assigns a new pane id (`pane.moved` event). If you omit an
optional `pane_id`, the server's focused pane is used.

| Method | Params | Result `type` → payload |
|---|---|---|
| `ping` | `{}` | `pong` → `version, protocol, capabilities?` |
| `session.snapshot` | `{}` | `session_snapshot` → `snapshot` (see §3) |
| `workspace.list` | `{}` | `workspace_list` → `workspaces[]` |
| `workspace.get` | `{workspace_id}` | `workspace_info` → `workspace` |
| `workspace.focus` | `{workspace_id}` | `workspace_info` → `workspace` |
| `workspace.create` | `{cwd?, focus=false, label?, env?{}, source_workspace_id? (0.9 only)}` | `workspace_created` → `workspace, tab, root_pane` |
| `workspace.rename` | `{workspace_id, label}` | `workspace_info` |
| `workspace.close` | `{workspace_id, close_group? (0.9 only)}` | `ok` → `{}` |
| `tab.list` | `{workspace_id?}` | `tab_list` → `tabs[]` |
| `tab.get` / `tab.focus` | `{tab_id}` | `tab_info` → `tab` |
| `tab.create` | `{workspace_id?, cwd?, focus=false, label?, env?}` | `tab_created` → `tab, root_pane` |
| `tab.rename` | `{tab_id, label}` | `tab_info` |
| `tab.close` | `{tab_id}` | `ok` |
| `pane.list` | `{workspace_id?}` | `pane_list` → `panes[]` |
| `pane.get` | `{pane_id}` | `pane_info` → `pane` |
| `pane.current` | `{caller_pane_id?}` | `pane_current` → `pane` (the focused pane if omitted) |
| `pane.focus` | `{pane_id}` | `pane_info` → `pane`. **Focuses any pane by id**, also switching workspace and tab. |
| `pane.focus_direction` | `{pane_id?, direction:"left\|right\|up\|down"}` | `pane_focus_direction` → `focus{changed, reason?, source_pane_id, focused_pane_id?, layout}` |
| `pane.split` | `{target_pane_id?, workspace_id?, direction:"right\|down", ratio?(default 0.5), cwd?, focus=false, env?, right_click?}` | `pane_info` → the **new** pane |
| `pane.zoom` | `{pane_id?, mode:"toggle"(default)\|"on"\|"off"}` | `pane_zoom` → `zoom{changed, zoom_changed, focus_changed, reason?, pane_id, focused_pane_id, zoomed, layout}` |
| `pane.resize` | `{pane_id?, direction, amount?}` (fraction; default 0.05, capped at 0.5) | `pane_resize` → `resize{changed, reason?, pane_id, focused_pane_id, layout}` |
| `layout.set_split_ratio` | `{tab_id?\|pane_id?, path:[bool], ratio}` | `layout_split_ratio_set` → `layout` (a LayoutDescription tree) |
| `pane.layout` | `{pane_id?}` | `pane_layout` → `layout` (PaneLayoutSnapshot, see §4) |
| `pane.close` | `{pane_id}` | `ok` (**no confirmation prompt**; the TUI asks, the API does not) |
| `pane.rename` | `{pane_id, label?}` | `pane_info` |
| `pane.send_text` | `{pane_id, text}` | `ok`. Raw bytes are written to the PTY, with no bracketed-paste wrapping. |
| `pane.send_keys` | `{pane_id, keys:["enter","ctrl+c","esc","up","f1","shift+tab","minus",...]}` | `ok` / `invalid_key` |
| `pane.send_input` | `{pane_id, text?, keys?}` | `ok` (text first, then keys) |
| `pane.read` | `{pane_id, source:"visible\|recent\|recent_unwrapped\|detection", lines?, format:"text"(default)\|"ansi", strip_ansi=true}` | `pane_read` → `read{pane_id, workspace_id, tab_id, source, format, text, revision, truncated}` |
| `pane.scroll` (0.9 only) | `{pane_id, offset_from_bottom}` | `pane_info` → `pane` |
| `agent.list` / `agent.get{target}` | | `agent_list` → `agents[]` / `agent_info` |
| `agent.focus` | `{target}` (pane id or agent name) | `agent_info` → `agent` |
| `notification.show` | `{title, body?, position?, sound?:"none\|done\|request"}` | `notification_show` → `shown, reason` |
| `events.subscribe` | see §2.1 | `subscription_started` → `{}`, then a stream |
| `events.wait` | `{match_event:{event:"pane_agent_status_changed", pane_id, agent_status}, timeout_ms?}` | `wait_matched` → `event` |

Example:
```json
→ {"id":"s1","method":"pane.split","params":{"target_pane_id":"w1:p1","direction":"right","focus":true}}
← {"id":"s1","result":{"type":"pane_info","pane":{"pane_id":"w1:p2","terminal_id":"...","workspace_id":"w1","tab_id":"w1:t1","focused":true,"agent_status":"unknown","revision":0}}}
```

**How API focus interacts with the TUI.**
- In **0.9.x**, each attached TUI client has its own workspace and tab view (`src/server/clients.rs`
  `ClientShellLocation`). A *socket* call to `workspace.focus`, `tab.focus`, `pane.focus`, or `agent.focus` succeeds,
  and `create` with `focus:true` also calls `focus_all_shell_clients_on_default_target()`, so **every attached TUI
  client jumps to that target** (`src/server/headless/client_views.rs` `handle_api_request_with_shutdown_check`).
  Tap-to-switch on Android therefore moves the herdr TUI running in the SSH PTY.
- In **0.8.2** there is a single server-side view, so a focus call changes it for everyone.

### 2.1 `events.subscribe`
`src/api/schema/events.rs` (`Subscription`, `EventEnvelope`, `EventData`, `SubscriptionEventEnvelope`) and
`src/api/subscriptions.rs`.

Request:
```json
{"id":"sub","method":"events.subscribe","params":{"subscriptions":[
  {"type":"workspace.created"},{"type":"workspace.updated"},{"type":"workspace.renamed"},
  {"type":"workspace.closed"},{"type":"workspace.focused"},{"type":"workspace.moved"},{"type":"workspace.reordered"},
  {"type":"tab.created"},{"type":"tab.closed"},{"type":"tab.focused"},{"type":"tab.renamed"},{"type":"tab.moved"},
  {"type":"pane.created"},{"type":"pane.closed"},{"type":"pane.updated"},{"type":"pane.focused"},
  {"type":"pane.moved"},{"type":"pane.exited"},{"type":"pane.agent_detected"},{"type":"layout.updated"},
  {"type":"pane.agent_status_changed","pane_id":"w1:p1"},
  {"type":"pane.agent_status_changed","pane_id":"w1:p2","agent_status":"blocked"}
]}}
```
Other parameter-free types: `workspace.metadata_updated`, `worktree.created`, `worktree.opened`,
`worktree.removed`. Pane-scoped types are `pane.output_matched` (`{pane_id, source, lines?, match:{type:"substring"|"regex", value}, strip_ansi?}`) and `pane.scroll_changed` (`{pane_id}`).

The first line back is `{"id":"sub","result":{"type":"subscription_started"}}`. Every later line is an event **with
no `id`**. There are two wire shapes:

1. **Lifecycle events.** `event` is **snake_case**, not dotted, and `data` repeats it as a `type` tag:
   ```json
   {"event":"pane_focused","data":{"type":"pane_focused","pane_id":"w1:p2","workspace_id":"w1"}}
   ```
2. **Pane-scoped subscription events.** `event` is **dotted** and `data` is untagged:
   ```json
   {"event":"pane.agent_status_changed","data":{"pane_id":"w1:p1","workspace_id":"w1","agent_status":"blocked",
     "agent":"claude","title":"...","display_agent":"...","state_labels":{"blocked":"needs approval"}}}
   ```

`data` per event (`EventData`):

| event | data fields |
|---|---|
| `workspace_created` / `workspace_updated` / `workspace_metadata_updated` | `workspace: WorkspaceInfo` |
| `workspace_closed` | `workspace_id, workspace?` |
| `workspace_renamed` | `workspace_id, label` |
| `workspace_moved` | `workspace_id, insert_index, workspaces[]` |
| `workspace_reordered` | `workspace_ids[], before_workspace_id?, workspaces[]` |
| `workspace_focused` | `workspace_id` |
| `tab_created` | `tab: TabInfo` |
| `tab_closed` / `tab_focused` | `tab_id, workspace_id` |
| `tab_renamed` | `tab_id, workspace_id, label` |
| `tab_moved` | `tab_id, workspace_id, insert_index, tabs[]` |
| `pane_created` / `pane_updated` | `pane: PaneInfo` |
| `pane_closed` / `pane_focused` / `pane_exited` | `pane_id, workspace_id` |
| `pane_moved` | `previous_pane_id, previous_workspace_id, previous_tab_id, pane, created_workspace?, created_tab?, closed_workspace_id?, closed_tab_id?` |
| `pane_agent_detected` | `pane_id, workspace_id, agent?, released?, final_status?` |
| `layout_updated` | `layout: PaneLayoutSnapshot` (one tab) |

Semantics:
- A focus change emits `workspace_focused`, `tab_focused`, and `pane_focused` together
  (`src/app/api.rs` `emit_focus_api_events`). Splits, resizes, zooms, closes, and swaps emit `layout_updated`.
- **There is no session-wide agent-status subscription.** `pane.agent_status_changed` requires `pane_id`.
  - The global `PaneAgentStatusChanged` event does exist in the event hub (`src/app/api.rs` `emit_pane_state_update`),
    but no `Subscription` variant exposes it unfiltered.
  - `pane_updated` is emitted for agent-name and terminal-title changes, **not** for status changes.
  - So: include one `pane.agent_status_changed` entry per pane, and **re-subscribe on a new connection** whenever the
    pane set changes (`pane_created`, `pane_closed`, `pane_moved`, `workspace_closed`).
  - With the `agent_status` filter, you get an immediate initial event if the pane already has that status.
  - Each pane-scoped entry also polls `pane.get` server-side every 100 ms while it is idle. That is cheap, but it
    scales with the number of panes.
- **If any referenced pane does not exist, the whole subscribe request fails** with an error, and the connection
  closes.
- Ordering is guaranteed only within one subscription entry, not across entries, and there is no shared sequence with
  `session.snapshot`.
- The recommended resync procedure (from the docs): subscribe, wait for `subscription_started`, call
  `session.snapshot` on another connection, treat events as invalidations, and re-read.

**Event retention.** The hub keeps the last **512** events across all types (`src/api/event_hub.rs` `MAX_EVENTS`).
- **0.9.3:** subscriptions start at the current sequence, with no replay. Batches are drained every tick. A reader that
  falls behind gets `{"id":"sub","error":{"code":"events_lost",...}}` and the connection closes.
- **0.8.2:** lifecycle subscriptions start at `last_sequence: 0`. They **replay up to 512 retained historical events**
  on subscribe, and emit **at most one event per subscription entry per 100 ms tick** (`subscription.poll` returns
  the first match only). There is no `events_lost` signal, so loss is silent (`v0.8.2: src/api/subscriptions.rs`,
  `server.rs` around line 725).
  - On 0.8.2, ignore events received before your snapshot completes, or better, use them only as "refresh" triggers.

---

## 3. Types
`src/api/schema/{workspaces,tabs,panes,agents,session,common}.rs`

```text
AgentStatus = "idle" | "working" | "blocked" | "done" | "unknown"
  ("done" = idle but not yet seen by the user; for workspace and tab values this is a rollup)

WorkspaceInfo { workspace_id, number:usize, label, focused:bool, pane_count, tab_count,
                active_tab_id, agent_status:AgentStatus, tokens?:{str:str}, worktree?:{repo_key,
                repo_name, repo_root, checkout_path, is_linked_worktree} }

TabInfo { tab_id, workspace_id, number, label, focused, pane_count, agent_status }

PaneInfo { pane_id, terminal_id, workspace_id, tab_id, focused:bool, cwd?, foreground_cwd?,
           restore_error? (0.9 only), label?, agent?, title?, terminal_title?, terminal_title_stripped?,
           display_agent?, agent_status, state_labels?:{status:str}, tokens?, agent_session?:{source,
           agent, kind, value}, scroll?:{offset_from_bottom, max_offset_from_bottom, viewport_rows},
           revision:u64 }

AgentInfo { terminal_id, name?, agent?, title?, terminal_title?, terminal_title_stripped?,
            display_agent?, agent_status, screen_detection_skipped?, state_labels?, tokens?,
            agent_session?, workspace_id, tab_id, pane_id, focused, launch_pending?,
            interactive_ready?, state_change_seq:u64, completion_seq? (0.9 only), cwd?,
            foreground_cwd?, revision }

SessionSnapshot { version, protocol, focused_workspace_id?, focused_tab_id?, focused_pane_id?,
                  workspaces[], tabs[], panes[], layouts:[PaneLayoutSnapshot], agents[] }
```

The snapshot is the best single bootstrap call, because it includes the layouts of every tab.

---

## 4. Geometry: `pane.layout` and `layout_updated`
`src/app/api/panes.rs` (`pane_layout_snapshot`):
```text
PaneLayoutSnapshot { workspace_id, tab_id, zoomed:bool, area:Rect, focused_pane_id,
                     panes:[{pane_id, focused, rect:Rect}],
                     splits:[{id, direction:"right"|"down", ratio:f32, rect:Rect}] }
Rect { x:u16, y:u16, width:u16, height:u16 }   // terminal cells
```

- Rects are in cells. They are computed from `state.view.terminal_area` and passed through `apply_pane_chrome`, so a
  **pane rect is the outer pane box, including borders and gaps, not the content area**. Split rects cover the whole
  split node.
- **0.8.2** renders the UI on the server. `terminal_area` is the real pane-area rect on the foreground client's screen,
  so it already includes the sidebar and tab-bar offsets. Rects are usable as screen coordinates for that client.
  - **[?]** Not verified on a live screen.
- **0.9.3** renders the UI in the client. When the foreground client is a client shell, the server sets
  `terminal_area = Rect(0, 0, effective_size)` before handling the API request
  (`src/server/headless.rs` around line 2986). Rects are then relative to the server's notion of the pane surface,
  with origin (0,0), **not** to the client's screen.
  - The client draws the sidebar, tab bar, and mobile header itself. In mobile layout (width ≤
    `ui.mobile_width_threshold`, default 64) the pane surface starts at row 2 (`src/client/shell/config.rs` `layout`).
  - Several clients can view different tabs at different sizes.
  - **[?]** I did not confirm whether `effective_size` is the full terminal size or the pane-surface size.
- **The layout is computed even when the tab is zoomed.** It lists every pane, with `zoomed:true`.
- **Recommendation:** do **not** map touches to panes yourself from `pane.layout`. Forward SGR mouse events into the
  PTY and let herdr hit-test (§6). Use `pane.layout` only for a schematic mini-map in the native drawer, using ratios
  and relative rects.

---

## 5. Differences between 0.8.2 and 0.9.3

| | 0.8.2 | 0.9.3 |
|---|---|---|
| `ping.protocol` | 20 | 22 |
| `capabilities` fields | `live_handoff`, `detached_server_daemon` | adds `endpoint_protocol_generation:1`, `surface_interest`, `health_check`, `ssh_agent_registration` |
| Methods only in 0.9.3 | | `pane.scroll`, `pane.clear`, `pane.edit_scrollback`, `pane.selection.read`, `pane.copy_motion`, `pane.copy_search`, `pane.link.activate`, `pane.link.resolve`, `integration.list`, `command.invoke`, `client_shell.surface.set` (returns `connection_local_only` on the socket), `product_announcement.dismiss`, `release_notes.dismiss`, `server.ssh_agent.register` |
| Methods only in 0.8.2 | `pane.graphics.info/set/clear/stream` | removed |
| `workspace.create` | | adds `source_workspace_id` |
| `workspace.close` | `{workspace_id}` only | adds `close_group` and the `workspace_group_close_required` error |
| Subscription semantics | replays history, one event per entry per tick, silent loss | starts at subscribe time, batched, `events_lost` |
| UI rendering | server renders everything | client shell renders the chrome; per-client views; API focus moves all clients |

Everything else the drawer needs is present in both versions with the same names: ping, snapshot, workspace, tab, and
pane list/get/focus/create/close/split/zoom/resize/layout/send_*/read, and every subscription type.

### The client-server binary protocol is not the JSON API
`src/protocol/wire.rs`. This is the TUI client ↔ server link over `herdr-client.sock`, the `data_dir` socket next to
`herdr.sock`.
- **Framing:** `[u32 LE length][bincode(serde) payload]`, with a 2 MiB frame cap.
- **Handshake:** `ClientMessage::Hello{version, cols, rows, cell px, requested_encoding: SemanticFrame|TerminalAnsi,
  keybindings, launch_mode}` is answered by `ServerMessage::Welcome{version, encoding, error}`.
- **0.8.x requires an exact version match** (`check_client_version`). 0.9 adds a separate "endpoint generation 1"
  layer (`src/protocol/endpoint.rs`), so a client and server built at different versions can work together.
- It carries ratatui-specific frame data and bincode-encoded Rust enums. **It is internal and not public.** The docs
  say: "The numbered binary protocol remains for same-install and internal operations … JSON API clients should
  ignore unknown fields". Do not target it.
- **About the "handshake version 19" in the user's log:** 19 is v0.8.0's `PROTOCOL_VERSION`. v0.8.2 uses 20. That
  line comes from `info!(version, ?encoding, "handshake succeeded")` and logs the server's Welcome version, which must
  equal the client's. So the log was written by a 0.8.0 binary pair, **or the Mac is really running a 0.8.0
  server/client**. Check with `herdr --version` and `ping`.

---

## 6. Mouse and keys in the TUI (over the PTY)

### Mouse modes
- **0.9.3** (`src/client/terminal_setup.rs` `set_mouse_capture`; `src/terminal_modes.rs`): first the client clears
  every mode (`?1006l ?1016l ?1015l ?1005l ?1003l ?1002l ?1000l ?9l`). Then, if mouse capture is enabled, it runs
  crossterm `EnableMouseCapture`, which writes **`?1000h ?1002h ?1003h ?1015h ?1006h`**. It adds `?1016h` (SGR pixels)
  only when requested and the cell geometry is exact.
- **0.8.2** also uses crossterm `EnableMouseCapture` (`src/client/mod.rs`, `src/main.rs`).
- **So herdr wants SGR 1006 encoding with any-motion tracking.** The Android terminal emulator should implement DECSET
  1000/1002/1003/1006 (and ideally 1004 focus events and 2004 bracketed paste, both of which herdr enables) and send
  `CSI < b ; x ; y M/m`.
- The config key is `[ui] mouse_capture = true` (the default). Pane apps that request mouse reporting still receive
  mouse events through herdr.

### Mouse actions handled
`src/client/shell/mouse.rs` in 0.9.3; `src/app/input/mouse.rs` in 0.8.2, which has a separate `handle_mobile_mouse`:
- Clicking a pane focuses it (`PaneFocus`).
- Dragging a split border resizes it (`ClientChromeDrag::PaneSplit`, which sends `layout.set_split_ratio`).
- The wheel scrolls the pane scrollback (`ui.mouse_scroll_lines = 3`), or is forwarded to the pane app when that app
  requests mouse reporting.
- Also handled: dragging the pane scrollbar, clicking the sidebar and tabs, drag-reordering tabs and workspaces,
  dragging the sidebar width, selecting text with left-drag (`copy_on_select = true`), and right-click opening the pane
  menu (`right_click_passthrough_modifier`, `pane.input.set`).
- Mobile layout (≤ 64 cols) keeps splits but has a 2-row header and a switcher menu instead of the sidebar.

### Default keybindings
Identical in 0.8.2 and 0.9.3, except that 0.9 adds `clear_pane` (unbound). Source: the config template in
`src/main.rs`, `[keys]`.
- Prefix is `ctrl+b`.
  - **General:** help `?`, settings `s`, detach `q`, reload config `shift+r`, open notification target `o`, workspace
    picker `w`, goto `g`.
  - **Workspaces:** new workspace `shift+n`, new worktree `shift+g`, rename workspace `shift+w`, close workspace
    `shift+d`.
  - **Tabs:** new tab `c`, rename tab `shift+t`, previous tab `p`, next tab `n`, switch tab `1..9`, close tab
    `shift+x`.
  - **Panes:** rename pane `shift+p`, edit scrollback `e`, focus pane `h` `j` `k` `l`, cycle pane `tab` and
    `shift+tab`, split vertical `v`, split horizontal `minus`, close pane `x`, zoom `z`, resize mode `r`, toggle
    sidebar `b`.
- Navigate mode uses Up/Down for workspaces and h/j/k/l for panes.
- `remote_image_paste = "ctrl+v"` is only active in `--remote`.
- `pane.send_keys` does **not** accept `prefix+` strings, so the drawer buttons must use the API, not keystrokes.

---

## 7. Design checklist for Android
1. **Bootstrap.**
   1. Run `ping` to get `version` and `protocol`.
   2. Open the subscription channel with the lifecycle types plus one `pane.agent_status_changed` per pane.
   3. Wait for `subscription_started`.
   4. Call `session.snapshot` on a separate channel.
2. **Use one channel per RPC.** Correlate replies by `id` anyway.
3. **On a pane-set change,** open a new subscription with the updated pane list, then close the old one. On
   `events_lost` or a disconnect, re-run step 1.
4. **Notifications:** fire on a `pane.agent_status_changed` with `agent_status == "blocked"` for a non-focused pane.
   Optionally fire on `done`.
5. **Tap-to-switch** is `pane.focus{pane_id}`. In 0.9 this also moves the TUI.
6. **Split, zoom, and close** use `pane.split`, `pane.zoom`, and `pane.close`. Close needs a confirmation in the
   Android UI.
7. **Gate 0.9-only methods on `version`,** and handle `invalid_request` gracefully.
