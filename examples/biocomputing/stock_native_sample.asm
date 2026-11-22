; x0 -> serial_exchange x0; x1 -> stock_native_inspect x0.
; Two read-only commands: reader status, then one authorized stock sample.
TYPE Target RECORD x INTEGER y INTEGER z INTEGER
TYPE Packet RECORD kind TEXT version INTEGER target Target request TEXT operation TEXT arguments ANY
TYPE Call RECORD p Packet e TEXT
TYPE Reply RECORD r ANY
TYPE View RECORD v ANY
TYPE Ids LIST TEXT
TYPE Sample RECORD device ANY session ANY components Ids
REG packet Packet
REG call Call
REG received Reply
REG view View
REG sample Sample
REG reply ANY
REG args ANY
REG state ANY
REG device ANY
REG session ANY
REG kind TEXT
REG expected TEXT
REG op TEXT
REG request TEXT
REG field TEXT
REG ids Ids
REG version INTEGER
REG sampling BOOLEAN
REG ok BOOLEAN
CONST kind "reader_command"
CONST expected "reader_reply"
CONST version 1
CONST sampling false
CONST op "status"
CONST args {}
CONST field "state"
send:
CORRELATION request
RECORD.MAKE packet kind kind version version target reader request request operation op arguments args
RECORD.MAKE call p packet e expected
XBUS.WRITE x0 call
XBUS.READ received x0
FIELD reply received r
HAS.FIELD ok reply field
JFALSE ok done
JFALSE sampling prepare
JMP done
prepare:
FIELD state reply state
FIELD device state device
FIELD session state session
CONST ids ["create_biotech:stock"]
RECORD.MAKE sample device device session session components ids
MOV args sample
CONST sampling true
CONST field "value"
CONST op "sample"
JMP send
done:
RECORD.MAKE view v reply
XBUS.WRITE x1 view
