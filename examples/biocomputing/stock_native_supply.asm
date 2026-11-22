; x0 shares handoff.x1 and ledger.x0; receive work only when handoff drives p0=3.
; x1 connects the packet/exchange client x0. No hidden host protocol.
; A submitted order is not delivery. The original source input is never ACKed here.
TYPE Context RECORD c ANY j TEXT i ANY e ANY s ANY
TYPE Target RECORD x INTEGER y INTEGER z INTEGER
TYPE Build RECORD t Target j TEXT i ANY s ANY o TEXT e ANY
TYPE Built RECORD c TEXT p ANY
TYPE Call RECORD p ANY e TEXT
TYPE Reply RECORD r ANY
REG context Context
REG build Build
REG built Built
REG call Call
REG received Reply
REG state ANY
REG items ANY
REG expected ANY
REG payload ANY
REG reply ANY
REG code ANY
REG job TEXT
REG operation TEXT
REG family TEXT
REG accepted TEXT
REG submitted TEXT
REG field TEXT
REG actual ANY
REG zero INTEGER
REG one INTEGER
REG two INTEGER
REG three INTEGER
REG status INTEGER
REG ok BOOLEAN
REG submitting BOOLEAN
CONST zero 0
CONST one 1
CONST two 2
CONST three 3
CONST job ""
CONST accepted "ok"
CONST submitted "submit"
CONST field "phase"
ready:
SLEEP one
SIO.READ status p0
EQUAL_INTEGER ok status three
JFALSE ok ready
XBUS.READ context x0
MOV reply context
FIELD code context c
VALUE.EQUALS ok code accepted
JFALSE ok done
FIELD job context j
FIELD expected context e
FIELD items context i
CONST state null
CONST operation "status"
CONST family "production_reply"
CONST submitting false
request:
RECORD.MAKE build t target j job i items s state o operation e expected
XBUS.WRITE x1 build
XBUS.READ received x1
FIELD reply received r
JFALSE submitting prepare
HAS.FIELD ok reply field
JFALSE ok done
VALUE.EQUALS ok operation submitted
JFALSE ok done
CONST operation "order"
JMP request
prepare:
FIELD code reply code
VALUE.EQUALS ok code accepted
JFALSE ok done
FIELD state reply state
FIELD actual state address
VALUE.EQUALS ok expected actual
JFALSE ok mismatch
CONST operation "submit"
CONST submitting true
JMP request
mismatch:
CONST reply {"code":"route_mismatch"}
done:
