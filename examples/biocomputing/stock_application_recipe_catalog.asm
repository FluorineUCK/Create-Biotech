; Receipt printer P. x0 -> Stock B.x1, x1 <- projector E.x1. No relay or dispatch.
; Read one fence, collect all rows, replace once. Never retry a rejected fence.
TYPE T RECORD x INTEGER y INTEGER z INTEGER
TYPE Call RECORD k BOOLEAN t T o TEXT a ANY
TYPE Result RECORD r ANY
TYPE Row RECORD ok BOOLEAN v ANY
TYPE List LIST ANY
TYPE Replace RECORD device ANY session ANY revision ANY entries List
REG call Call
REG res Result
REG row Row
REG list List
REG replace Replace
REG reply ANY
REG v ANY
REG a ANY
REG d ANY
REG s ANY
REG r ANY
REG op TEXT
REG expected TEXT
REG reader BOOLEAN
REG put BOOLEAN
REG ok BOOLEAN
REG zero INTEGER
REG one INTEGER
CONST zero 0
CONST one 1
GREATER_INTEGER ok pages zero
JFALSE ok bad
CONST reader false
CONST put false
CONST expected "ok"
CONST op "status"
CONST a {}
send:
RECORD.MAKE call k reader t output o op a a
XBUS.WRITE x0 call
XBUS.READ res x0
FIELD reply res r
JFALSE put begin
RET
begin:
FIELD v reply code
VALUE.EQUALS ok v expected
JFALSE ok done
FIELD v reply state
FIELD d v device
FIELD s v session
FIELD r v revision
LIST.MAKE list
next:
XBUS.READ row x1
FIELD ok row ok
FIELD v row v
JFALSE ok reject
LIST.APPEND list list v
SUBTRACT_INTEGER pages pages one
EQUAL_INTEGER ok pages zero
JFALSE ok next
RECORD.MAKE replace device d session s revision r entries list
MOV a replace
CONST op "replace"
CONST put true
JMP send
bad:
CONST reply {"code":"invalid_page_count"}
RET
reject:
MOV reply v
done:
