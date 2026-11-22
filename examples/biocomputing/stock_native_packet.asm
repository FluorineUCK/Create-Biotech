; Packet builder and real exchange client. x0 -> supply; x1 shares exchange.x0 with sampler.x0.
; Start the supply receiver before this service. Supply begins only after sampling/conversion completes.
TYPE Target RECORD x INTEGER y INTEGER z INTEGER
TYPE Input RECORD t Target j TEXT i ANY s ANY o TEXT e ANY
TYPE Packet RECORD kind TEXT version INTEGER target Target request TEXT operation TEXT arguments ANY
TYPE Data RECORD items ANY
TYPE Submit RECORD device ANY session ANY order TEXT data Data
TYPE Query RECORD order TEXT
TYPE Call RECORD p Packet e TEXT
TYPE Reply RECORD r ANY
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
REG code TEXT
REG request TEXT
REG state ANY
REG expected ANY
REG actual ANY
REG device ANY
REG session ANY
REG items ANY
REG arguments ANY
REG payload ANY
REG status INTEGER
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
CONST arguments {}
CONST payload null
CONST code "route_mismatch"
VALUE.EQUALS ok operation expected_operation
JFALSE ok submission
JMP build
submission:
VALUE.EQUALS ok operation query_operation
JFALSE ok submit_materials
RECORD.MAKE query order job
MOV arguments query
JMP build
submit_materials:
FIELD state input s
FIELD device state device
FIELD session state session
FIELD items input i
RECORD.MAKE data items items
RECORD.MAKE submit device device session session order job data data
MOV arguments submit
build:
CORRELATION request
RECORD.MAKE packet kind kind version version target target request request operation operation arguments arguments
RECORD.MAKE call p packet e family
XBUS.WRITE x1 call
XBUS.READ received x1
XBUS.WRITE x0 received
JMP next
