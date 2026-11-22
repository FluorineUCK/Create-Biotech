-- Run on the authorized CC computer: terminal-cc-publish <bridge> [publish|take] [title]
local side, operation, title = ...
if not side then error("Usage: terminal-cc-publish <bridge> [publish|take] [title]", 0) end
local bridge = peripheral.wrap(side)
if not bridge or not bridge.publisherStatus then error("Attach a Biocomputing terminal first", 0) end
local status = bridge.publisherStatus()

if operation == "take" then
    if not status.next_event then print("No events waiting"); return end
    local event = bridge.takeEvent(status.device, status.session, status.revision, status.next_event)
    print(("%s: %s (layout %s)"):format(event.kind, event.name, event.layout_revision))
    print(event.values_json or event.values_archive_snbt)
    return event
end
if operation and operation ~= "publish" then error("Choose publish or take", 0) end

local function literal(value) return { kind = "literal", value = value } end
local layout = { version = 3, root = "main", nodes = {
    main = { kind = "column", children = { "caption", "entry", "done" } },
    caption = { kind = "text", value = {
        kind = "path", input = "output", steps = { { kind = "key", name = "title" } }
    } },
    entry = { kind = "input", initial = literal(""), enabled = literal(true),
        entry = "submit_note", argument = "note", inputs = literal({ source = "cc" }) },
    done = { kind = "button", title = literal("Done"), enabled = literal(true),
        entry = "done", inputs = literal({ source = "cc" }) },
} }
local roots = { output = { title = title or "Published from CC" } }
local result = bridge.publishPage(status.device, status.session, status.revision,
    textutils.serializeJSON(layout), textutils.serializeJSON(roots))
if result.code ~= "published" then error(result.code, 0) end
print("Published layout " .. result.state.revision)
return result.state
