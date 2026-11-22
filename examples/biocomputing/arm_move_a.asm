; Select one native pickup/deposit pair. Each explicit run admits at most one native pickup.
; Read the reply action ID and query arm_status separately for completion.
TYPE Target RECORD x INTEGER y INTEGER z INTEGER
TYPE Packet RECORD kind TEXT version INTEGER target Target request TEXT operation TEXT arguments ANY
TYPE Call RECORD p Packet e TEXT
TYPE Reply RECORD r ANY
TYPE Action RECORD token ANY input INTEGER output INTEGER
REG packet Packet
REG call Call
REG received Reply
REG action Action
REG args ANY
REG state ANY
REG token ANY
REG code ANY
REG kind TEXT
REG expected TEXT
REG op TEXT
REG request TEXT
REG version INTEGER
REG queryOk TEXT
REG ok BOOLEAN
REG reply ANY
CONST kind "component_settings_command"
CONST expected "component_settings_reply"
CONST version 1
CONST op "arm_status"
CONST args {}
CONST queryOk "ok"
JFALSE apply send
CONST op "arm_prepare"
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
FIELD token state token
RECORD.MAKE action token token input input output output
MOV args action
CONST apply false
CONST op "arm_move"
JMP send
done:
