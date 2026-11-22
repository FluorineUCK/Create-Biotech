; Explicit operation table: seek/select, open/read_info, software download, then actual read_close.
TYPE Row RECORD o TEXT d ANY e TEXT l BOOLEAN
TYPE Context RECORD r Row s ANY d ANY
TYPE Result RECORD ok BOOLEAN value ANY
REG context Context
REG row Row
REG tx Result
REG result Result
REG op TEXT
REG download TEXT
REG flag BOOLEAN
REG i INTEGER
REG one INTEGER
REG seq ANY
REG data ANY
REG reply ANY
REG retained ANY
CONST i 0
CONST one 1
CONST seq 0
CONST retained null
CONST download "download"
next:
LIST.GET row commands i
FIELD op row o
FIELD data row d
VALUE.EQUALS flag op download
JFALSE flag dispatch
MOV data retained
dispatch:
RECORD.MAKE context r row s seq d data
JFALSE flag normal_dispatch
XBUS.WRITE x1 context
XBUS.READ tx x1
JMP response
normal_dispatch:
XBUS.WRITE x0 context
XBUS.READ tx x0
response:
FIELD flag tx ok
JFALSE flag done
FIELD flag row l
JFALSE flag remember
CONST flag true
RECORD.MAKE tx ok flag value retained
JMP done
remember:
VALUE.EQUALS flag op download
JFALSE flag normal
FIELD retained tx value
JMP advance
normal:
FIELD reply tx value
FIELD seq reply sequence
FIELD retained reply value
advance:
ADD_INTEGER i i one
JMP next
done:
MOV result tx
