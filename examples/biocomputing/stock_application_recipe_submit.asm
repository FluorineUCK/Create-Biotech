; Probe the real port first. Null/mismatching approved_scope returns the scope for explicit review.
; Pin scope survives sessions but not device/source, grant revision, route or location changes.
; The same business id is used for submit and query. A conflict with a phase is NOT success.
TYPE Target RECORD x INTEGER y INTEGER z INTEGER
TYPE Data RECORD items ANY
TYPE Command RECORD t Target j TEXT i Data s ANY o TEXT e ANY
TYPE Receipt RECORD r ANY
TYPE Shipment RECORD j TEXT a TEXT i ANY
TYPE Probe RECORD code TEXT scope ANY
REG packet Command
REG received Receipt
REG shipment Shipment
REG data Data
REG probe Probe
REG reply ANY
REG job TEXT
REG address TEXT
REG items ANY
REG state ANY
REG scope ANY
REG actual ANY
REG code ANY
REG op TEXT
REG wanted TEXT
REG ok BOOLEAN
CONST job ""
CONST state null
CONST data {"items":[]}
CONST op "status"
send:
RECORD.MAKE packet t target j job i data s state o op e approved_scope
XBUS.WRITE x1 packet
XBUS.READ received x1
FIELD reply received r
CONST wanted "order"
VALUE.EQUALS ok op wanted
JFALSE ok inspect
RET
inspect:
FIELD code reply code
CONST wanted "submit"
VALUE.EQUALS ok op wanted
JFALSE ok check_status
CONST wanted "accepted"
VALUE.EQUALS ok code wanted
JFALSE ok existing
JMP query
existing:
CONST wanted "existing"
VALUE.EQUALS ok code wanted
JFALSE ok done
query:
CONST op "order"
JMP send
check_status:
CONST wanted "ok"
VALUE.EQUALS ok code wanted
JFALSE ok done
FIELD state reply state
FIELD scope state scope
VALUE.EQUALS ok scope approved_scope
JFALSE ok unapproved
XBUS.READ shipment x0
FIELD job shipment j
FIELD address shipment a
FIELD actual scope address
VALUE.EQUALS ok address actual
JFALSE ok mismatch
FIELD items shipment i
RECORD.MAKE data items items
CONST op "submit"
JMP send
unapproved:
CONST wanted "scope_not_approved"
RECORD.MAKE probe code wanted scope scope
MOV reply probe
RET
mismatch:
CONST reply {"code":"route_mismatch"}
done:
