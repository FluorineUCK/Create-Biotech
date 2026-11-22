; Reserve one native transport-processing cycle without releasing the physical redstone lock.
TYPE Target RECORD x INTEGER y INTEGER z INTEGER
TYPE Packet RECORD kind TEXT version INTEGER target Target request TEXT operation TEXT arguments ANY
TYPE Call RECORD p Packet e TEXT
TYPE Reply RECORD r ANY
TYPE Permit RECORD token ANY
REG packet Packet
REG call Call
REG received Reply
REG permit Permit
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
CONST op "deployer_status"
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
FIELD token state token
RECORD.MAKE permit token token
MOV args permit
CONST apply false
CONST op "deployer_process"
JMP send
done:
