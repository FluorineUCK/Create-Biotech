-- Run on the authorized CC computer: terminal-cc-display <bridge> [monitor|term] [row]
-- This only draws one snapshot. It does not receive or submit UI events.
local name, destination, offset = ...
if not name then error("Usage: terminal-cc-display <bridge> [monitor|term] [row]", 0) end
local bridge = peripheral.wrap(name)
if not bridge or not bridge.readFrame then error("Attach a Biocomputing terminal first", 0) end
local output = (not destination or destination == "term") and term.current() or peripheral.wrap(destination)
if not output or not output.getSize then error("Choose your terminal or an attached monitor", 0) end
local width, height = output.getSize()
local start = 0
if offset then start = tonumber(offset) end
if not start or start < 0 or start % 1 ~= 0 then error("Row must be a nonnegative integer", 0) end

local frame = bridge.readFrame(width, height, start)
output.clear()
for i, row in ipairs(frame.rows) do
    output.setCursorPos(1, i)
    output.write(row)
end
if start + #frame.rows < frame.totalRows or frame.clipped or frame.substituted then
    -- Report projection limits via CraftOS print; do not overwrite the chosen monitor.
    if destination and destination ~= "term" then
        print(("Rows %d-%d/%d; clipped=%s, character substitution=%s"):format(
            start + 1, start + #frame.rows, frame.totalRows, tostring(frame.clipped), tostring(frame.substituted)))
    end
end
return frame
