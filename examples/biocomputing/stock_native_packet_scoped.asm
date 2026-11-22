; Scope-fenced public packet builder. x0 -> caller; x1 -> serial_exchange.x0.
; e is the ORIGINAL archived status.scope, not an address or the latest status.scope.
; s may contain the current status (fresh session); changing e would discard the recovery fence.
; This variant never substitutes the current scope, retries, or expands a material request in Java.
; i is a typed {items: list} record, constructed by the calling block's RECORD.MAKE.
TYPE Target RECORD x INTEGER y INTEGER z INTEGER
TYPE Data RECORD items ANY
TYPE Input RECORD t Target j TEXT i Data s ANY o TEXT e ANY
TYPE Packet RECORD kind TEXT version INTEGER target Target request TEXT operation TEXT arguments ANY
TYPE Submit RECORD device ANY session ANY order TEXT data Data scope ANY
TYPE Query RECORD order TEXT scope ANY
TYPE Call RECORD p Packet e TEXT
TYPE Reply RECORD r ANY
REG scope ANY
REG query Query
REG call Call
REG received Reply
REG family TEXT
REG query_operation TEXT
REG input Input
REG packet Packet
REG data Data
REG submit Submit
REG target Target
REG job TEXT
REG operation TEXT
REG expected_operation TEXT
REG kind TEXT
REG request TEXT
REG state ANY
REG device ANY
REG session ANY
REG arguments ANY
REG version INTEGER
REG ok BOOLEAN
CONST kind "production_command"
CONST version 1
CONST expected_operation "status"
CONST query_operation "order"
CONST family "production_reply"
next:
XBUS.READ input x0
FIELD target input t
FIELD job input j
FIELD operation input o
CONST arguments {"scope":true}
VALUE.EQUALS ok operation expected_operation
JFALSE ok submission
JMP build
submission:
FIELD scope input e
VALUE.EQUALS ok operation query_operation
JFALSE ok submit_materials
RECORD.MAKE query order job scope scope
MOV arguments query
JMP build
submit_materials:
FIELD state input s
FIELD device state device
FIELD session state session
FIELD data input i
RECORD.MAKE submit device device session session order job data data scope scope
MOV arguments submit
build:
CORRELATION request
RECORD.MAKE packet kind kind version version target target request request operation operation arguments arguments
RECORD.MAKE call p packet e family
XBUS.WRITE x1 call
XBUS.READ received x1
XBUS.WRITE x0 received
JMP next
