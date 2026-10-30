---
description: Select and test a TermiNow host for this session via the Local AI Bridge
---

Connect this Claude Code session to a TermiNow SSH host, using only the `terminow-hosts` MCP
server's tools. Do not run shell commands to change the selection, and never print secrets.

Steps:

1. Call the `bridge_status` tool. If a host is already selected, report it and ask whether to keep
   it. If the tool errors because the bridge is unavailable, tell the user to open TermiNow, turn the
   Local AI Bridge on, and run Connect & Test, then stop.
2. Call the `hosts_list` tool to list the hosts this environment may use (host_id, alias, connection
   type, capabilities). If the list is empty, tell the user to enable AI access for a host in
   TermiNow, then stop.
3. Decide the target host from the user's text in "$ARGUMENTS":
   - If it matches exactly one host_id, use that host_id.
   - Else if it uniquely matches one alias, use that host's host_id.
   - If it is missing, ambiguous, or matches none, show the list and ask the user which host_id to
     use. Never guess, and never pick a host just because it is the only recently used one.
4. Call the `select_host` tool with the chosen host_id. Selecting does not grant access.
5. Call the `ssh_test` tool (it now defaults to the selected host) to verify the connection.
6. Report the selected host, its allowed capabilities, and the ssh_test result (reachable,
   authenticated, host key known). If any tool returns an error, report its message verbatim — it is
   already sanitized — and do not retry blindly.

After this, `ssh_exec` and `ssh_test` may be called without a host_id and will use the selected host.
Use `/bridge` again to switch hosts, or the `disconnect_host` tool to clear the selection.
