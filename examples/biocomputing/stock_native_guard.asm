; Verify delivery before forwarding to the explicit ACK encoder.
; x0 shares finish.x0, p0 reads finish.p1; x1 connects ACK encoder x0.
; No automatic retry: an ambiguous ACK reply remains visible, never ACK another head.
TYPE Completion RECORD s ANY j TEXT r ANY
TYPE Verified RECORD s ANY j TEXT r ANY ok BOOLEAN
TYPE Values LIST ANY
TYPE Rejected RECORD code TEXT receipt ANY
REG input Completion
REG result Verified
REG rejected Rejected
REG reason TEXT
REG source ANY
REG head ANY
REG reply ANY
REG code ANY
REG phase ANY
REG milestone ANY
REG order ANY
REG address ANY
REG expected_address ANY
REG wanted Values
REG actual Values
REG job TEXT
REG accepted TEXT
REG completed TEXT
REG delivered TEXT
REG field TEXT
REG one INTEGER
REG zero INTEGER
REG selector INTEGER
REG ok BOOLEAN
CONST one 1
CONST zero 0
CONST accepted "ok"
CONST completed "complete"
CONST delivered "materials_delivered"
ready:
SLEEP one
SIO.READ selector p0
EQUAL_INTEGER ok selector one
JFALSE ok ready
XBUS.READ input x0
FIELD reply input r
FIELD job input j
FIELD source input s
TEXT.LENGTH selector job
GREATER_INTEGER ok selector zero
JFALSE ok reject
CONST field "milestone"
HAS.FIELD ok reply field
JFALSE ok reject
FIELD head source head
FIELD expected_address head address
FIELD code reply code
FIELD phase reply phase
FIELD milestone reply milestone
FIELD order reply order
FIELD address reply address
LIST.MAKE wanted accepted completed delivered job expected_address
LIST.MAKE actual code phase milestone order address
VALUE.EQUALS ok actual wanted
JFALSE ok reject
JMP emit
reject:
CONST ok false
CONST reason "delivery_not_verified"
RECORD.MAKE rejected code reason receipt reply
MOV reply rejected
emit:
RECORD.MAKE result s source j job r reply ok ok
XBUS.WRITE x1 result
