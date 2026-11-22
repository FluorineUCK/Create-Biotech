; Explicit operation table: seek/select, open/read_info, software download, then actual read_close.
TYPE Row RECORD o TEXT d ANY e TEXT l BOOLEAN
TYPE Context RECORD r Row s ANY d ANY
TYPE Result RECORD ok BOOLEAN value ANY
TYPE Command RECORD n INTEGER c TEXT
REG page INTEGER
REG begin INTEGER
REG zero INTEGER
REG high INTEGER
REG context Context
REG row Row
REG tx Result
REG saved Result
REG result Command
REG download INTEGER
REG flag BOOLEAN
REG i INTEGER
REG one INTEGER
REG seq ANY
REG data ANY
REG reply ANY
REG retained ANY
CONST i 0
CONST saved {"ok":false,"value":null}
CONST zero 0
CONST high 15
CONST begin 7
SIO.WRITE p0 zero
CONST one 1
CONST seq 0
CONST retained null
CONST download 9
next:
LIST.GET row commands i
FIELD data row d
EQUAL_INTEGER flag i download
JFALSE flag dispatch
MOV data retained
dispatch:
RECORD.MAKE context r row s seq d data
JFALSE flag normal_dispatch
SIO.WRITE p0 one
XBUS.WRITE x1 context
SIO.WRITE p0 zero
XBUS.READ tx x1
FIELD flag tx ok
JFALSE flag done
MOV saved tx
JMP advance
normal_dispatch:
XBUS.WRITE x0 context
XBUS.READ tx x0
response:
FIELD flag tx ok
JFALSE flag done
FIELD flag row l
JFALSE flag remember
MOV tx saved
JMP done
remember:
FIELD reply tx value
FIELD seq reply sequence
FIELD retained reply value
advance:
ADD_INTEGER i i one
JMP next
done:
; The pager determines the next address. Do not open it until the previous close or failure was reported.
SIO.WRITE p0 high
XBUS.WRITE x1 tx
SIO.WRITE p0 zero
XBUS.READ result x1
FIELD page result n
LESS_INTEGER flag page zero
JFALSE flag again
RET
again:
MOV i begin
LIST.GET row commands i
MOV data page
CONST flag false
JMP dispatch
