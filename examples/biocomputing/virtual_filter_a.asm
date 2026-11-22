; A.x0 connects to the public serial_exchange program's x0.
; Install with target: Target, rule: Rule, apply: BOOLEAN as initial registers.
; apply=false queries only; true issues one set after receiving a live status.
TYPE Target RECORD x INTEGER y INTEGER z INTEGER
TYPE Rule RECORD domain TEXT mode TEXT matchers ANY
TYPE Packet RECORD kind TEXT version INTEGER target Target request TEXT operation TEXT arguments ANY
TYPE Call RECORD p Packet e TEXT
TYPE Reply RECORD r ANY
TYPE SetArgs RECORD state ANY rule Rule
REG packet Packet
REG call Call
REG received Reply
REG action SetArgs
REG args ANY
REG state ANY
REG code ANY
REG kind TEXT
REG expected TEXT
REG op TEXT
REG request TEXT
REG version INTEGER
REG queryOk TEXT
REG ok BOOLEAN
REG reply ANY
CONST kind "virtual_filter_command"
CONST expected "virtual_filter_reply"
CONST version 1
CONST op "status"
CONST args {}
CONST queryOk "ok"
send:
CORRELATION request
RECORD.MAKE packet kind kind version version target target request request operation op arguments args
RECORD.MAKE call p packet e expected
XBUS.WRITE x0 call
XBUS.READ received x0
FIELD reply received r
JFALSE apply done
FIELD code reply code
VALUE.EQUALS ok code queryOk
JFALSE ok done
FIELD state reply state
RECORD.MAKE action state state rule rule
MOV args action
CONST apply false
CONST op "set"
JMP send
done:
