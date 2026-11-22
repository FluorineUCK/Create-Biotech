; Read the adjacent native Mechanical Arm via the public Serial Exchange driver.
TYPE Target RECORD x INTEGER y INTEGER z INTEGER
TYPE Packet RECORD kind TEXT version INTEGER target Target request TEXT operation TEXT arguments ANY
TYPE Call RECORD p Packet e TEXT
TYPE Reply RECORD r ANY
REG packet Packet
REG call Call
REG received Reply
REG args ANY
REG kind TEXT
REG expected TEXT
REG op TEXT
REG request TEXT
REG version INTEGER
REG reply ANY
CONST kind "component_settings_command"
CONST expected "component_settings_reply"
CONST version 1
CONST op "arm_status"
CONST args {}
CORRELATION request
RECORD.MAKE packet kind kind version version target target request request operation op arguments args
RECORD.MAKE call p packet e expected
XBUS.WRITE x0 call
XBUS.READ received x0
FIELD reply received r
