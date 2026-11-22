; Use the public serial_exchange program across an explicit A.x0 ↔ B.x0 connection.
TYPE Target RECORD x INTEGER y INTEGER z INTEGER
TYPE Packet RECORD kind TEXT version INTEGER target Target request TEXT operation TEXT arguments ANY
TYPE Call RECORD p Packet e TEXT
TYPE Reply RECORD r ANY
TYPE Claim RECORD state ANY sequence ANY
REG claim Claim
REG next_event ANY
REG event_count ANY
REG empty_count INTEGER
REG packet Packet
REG call Call
REG received Reply
REG args ANY
REG state ANY
REG kind TEXT
REG expected TEXT
REG op TEXT
REG request TEXT
REG state_key TEXT
REG version INTEGER
REG flag BOOLEAN
REG queried BOOLEAN
REG reply ANY
CONST kind "terminal_ui_command"
CONST expected "terminal_ui_reply"
CONST version 1
CONST op "status"
CONST args {}
CONST queried false
send:
CORRELATION request
RECORD.MAKE packet kind kind version version target target request request operation op arguments args
RECORD.MAKE call p packet e expected
XBUS.WRITE x0 call
XBUS.READ received x0
FIELD reply received r
JFALSE queried prepare
JMP done
prepare:
JFALSE execute done
CONST queried true
CONST state_key "state"
HAS.FIELD flag reply state_key
JFALSE flag done
FIELD state reply state
FIELD event_count state event_count
CONST empty_count 0
VALUE.EQUALS flag event_count empty_count
JFALSE flag claim_event
JMP done
claim_event:
FIELD next_event state next_event
RECORD.MAKE claim state state sequence next_event
MOV args claim
CONST op "take_event"
JMP send
done:
