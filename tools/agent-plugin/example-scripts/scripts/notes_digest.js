// How many notes the user keeps and the latest three, through a host tool: list_notes of an MCP server the host has
// configured as "notes". A script may call a host tool the package named in hostTools when the tool says it only
// reads; in a run without that tool, host.tool throws an error saying so.
function run() {
  const listed = host.tool("mcp__notes__list_notes", {});
  const notes = Array.isArray(listed.notes) ? listed.notes : [];
  host.log("read " + notes.length + " notes");
  return { count: notes.length, latest: notes.slice(-3) };
}
