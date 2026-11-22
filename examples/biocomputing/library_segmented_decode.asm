; Typed local driver boundary, not a wire command. x0 receives explicitly collected segments.
; Source metadata comes from a previously authorized Library read_info, not from this decoder.
TYPE Parts LIST NBT
TYPE Request RECORD p Parts k TEXT v ANY s ANY
TYPE Maybe OPTIONAL ANY
TYPE Decoded RECORD code TEXT value Maybe
TYPE Snapshot RECORD kind TEXT version ANY value ANY
TYPE Reply RECORD sequence ANY value Snapshot
TYPE Result RECORD ok BOOLEAN value ANY
REG request Request
REG parts Parts
REG bytes NBT
REG decoded Decoded
REG snapshot Snapshot
REG reply Reply
REG result Result
REG kind TEXT
REG version ANY
REG seq ANY
REG actual ANY
REG code TEXT
REG ok TEXT
REG flag BOOLEAN
XBUS.READ request x0
FIELD parts request p
FIELD kind request k
FIELD version request v
FIELD seq request s
DROP request
BYTES.JOIN bytes parts capacity
DROP parts
VALUE.DECODE decoded bytes workspace
DROP bytes
FIELD code decoded code
CONST ok "ok"
VALUE.EQUALS flag code ok
JFALSE flag invalid
FIELD actual decoded value
RECORD.MAKE snapshot kind kind version version value actual
RECORD.MAKE reply sequence seq value snapshot
RECORD.MAKE result ok flag value reply
JMP done
invalid:
RECORD.MAKE result ok flag value decoded
done:
XBUS.WRITE x0 result
