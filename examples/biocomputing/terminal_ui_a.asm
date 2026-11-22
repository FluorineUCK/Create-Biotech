; Use the public serial_exchange program across an explicit A.x0 ↔ B.x0 connection.
TYPE Target RECORD x INTEGER y INTEGER z INTEGER
TYPE Packet RECORD kind TEXT version INTEGER target Target request TEXT operation TEXT arguments ANY
TYPE Call RECORD p Packet e TEXT
TYPE Reply RECORD r ANY
TYPE Publication RECORD state ANY layout ANY roots ANY
REG publication Publication
REG selected_layout ANY
REG packet Packet
REG call Call
REG received Reply
REG args ANY
REG state ANY
REG count ANY
REG zero INTEGER
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
MOV selected_layout home_layout
FIELD count state event_count
CONST zero 0
VALUE.EQUALS flag count zero
JFALSE flag choose_form
JMP publish
choose_form:
MOV selected_layout form_layout
publish:
RECORD.MAKE publication state state layout selected_layout roots roots
MOV args publication
CONST op "publish"
JMP send
done:
