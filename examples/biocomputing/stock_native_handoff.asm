; x0 shares sampler.x1 and preparer.x0. Wait for preparer p0 -> p1 before reading Work.
; x1 shares ledger.x0 and supply.x0. p0=1 selects ledger; p0=3 selects supply.
; This is an explicit software handshake, not a privileged hardware protocol.
TYPE View RECORD v ANY
TYPE Work RECORD s ANY v ANY
TYPE Identity RECORD d ANY h ANY
TYPE Binding RECORD c TEXT j TEXT
TYPE Context RECORD c ANY j TEXT i ANY e ANY s ANY
TYPE Target RECORD x INTEGER y INTEGER z INTEGER
TYPE Packet RECORD kind TEXT version INTEGER target Target request TEXT operation TEXT arguments ANY
TYPE Call RECORD p Packet e TEXT
TYPE Reply RECORD r ANY
REG initial View
REG work Work
REG identity Identity
REG binding Binding
REG context Context
REG call Call
REG received Reply
REG source ANY
REG result ANY
REG head ANY
REG state ANY
REG device ANY
REG items ANY
REG expected ANY
REG code ANY
REG job TEXT
REG accepted TEXT
REG zero INTEGER
REG one INTEGER
REG three INTEGER
REG status INTEGER
REG ok BOOLEAN
CONST zero 0
CONST one 1
CONST three 3
CONST job ""
CONST source null
CONST items null
CONST expected null
CONST accepted "ok"
ready:
SLEEP one
SIO.READ status p1
EQUAL_INTEGER ok status one
JFALSE ok ready
XBUS.READ work x0
FIELD result work v
FIELD code result c
VALUE.EQUALS ok code accepted
JFALSE ok emit
FIELD source work s
FIELD state source state
FIELD device state device
FIELD head source head
RECORD.MAKE identity d device h head
SIO.WRITE p0 one
XBUS.WRITE x1 identity
XBUS.READ binding x1
FIELD code binding c
FIELD job binding j
TEXT.LENGTH status job
GREATER_INTEGER ok status zero
JFALSE ok emit
FIELD expected head address
FIELD items result i
CONST code "ok"
emit:
SIO.WRITE p0 three
RECORD.MAKE context c code j job i items e expected s source
XBUS.WRITE x1 context
SIO.WRITE p0 zero
