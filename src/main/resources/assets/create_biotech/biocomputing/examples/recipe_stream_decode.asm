; Dedicated download branch: x0 receives a software context; x1 collects explicit segments.
; Source metadata comes from a previously authorized Library read_info, not from this decoder.
TYPE Parts LIST NBT
TYPE Request RECORD p Parts k TEXT v ANY
TYPE Maybe OPTIONAL ANY
TYPE Decoded RECORD code TEXT value Maybe
TYPE Snapshot RECORD kind TEXT version ANY value ANY
TYPE MaybeRequest OPTIONAL Request
TYPE CheckedRequest RECORD ok BOOLEAN value MaybeRequest
REG checked CheckedRequest
REG maybe MaybeRequest
REG raw ANY
TYPE Row RECORD o TEXT d ANY e TEXT l BOOLEAN
TYPE Context RECORD r Row s ANY d ANY
TYPE Result RECORD ok BOOLEAN value ANY
REG context Context
REG tx Result
REG flag BOOLEAN
REG request Request
REG parts Parts
REG bytes NBT
REG decoded Decoded
REG snapshot Snapshot
REG kind TEXT
REG version ANY
REG actual ANY
REG code TEXT
REG ok TEXT
XBUS.READ context x0
XBUS.WRITE x1 context
XBUS.READ tx x1
FIELD flag tx ok
JFALSE flag done
FIELD raw tx value
VALUE.CHECK checked raw
FIELD maybe checked value
REQUIRE_VALUE request maybe
DROP raw
DROP tx
DROP checked
DROP maybe
FIELD parts request p
FIELD kind request k
FIELD version request v
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
RECORD.MAKE tx ok flag value snapshot
JMP done
invalid:
RECORD.MAKE tx ok flag value decoded
done:
XBUS.WRITE x0 tx
