; Finite communication task. Root drives SIO p0 from the public selector cell.
; selector: 0=control, 1=download owns x1, 2=final result for store.
; x0 uses session_buffer short replies {s:sequence,v:value}; failures stay unchanged.
; x1 is selected, not a hidden extra port. Pair this program with the matching buffer.
TYPE Cell REF INTEGER RW
REG cell Cell
TYPE Row RECORD o TEXT d ANY e TEXT l BOOLEAN
TYPE Context RECORD r Row s ANY d ANY
TYPE Result RECORD ok BOOLEAN value ANY
TYPE Rows LIST Row
TYPE Request RECORD c Rows m ANY
REG request Request
REG commands Rows
REG context Context
REG row Row
REG tx Result
REG verified Result
REG op TEXT
REG write TEXT
REG download TEXT
REG i INTEGER
REG zero INTEGER
REG one INTEGER
REG finished INTEGER
REG seq ANY
REG data ANY
REG reply ANY
REG retained ANY
REG flag BOOLEAN
REG downloading BOOLEAN
REF.ADDRESS cell selector
CONST zero 0
CONST one 1
CONST finished 2
REF.WRITE cell zero
XBUS.READ request x1
FIELD commands request c
MOV i zero
MOV seq zero
CONST retained null
CONST verified {"ok":false,"value":"archive_readback_missing"}
CONST write "write"
CONST download "download"
next:
LIST.GET row commands i
FIELD op row o
FIELD data row d
VALUE.EQUALS flag op write
JFALSE flag check_download
FIELD data request m
check_download:
VALUE.EQUALS downloading op download
JFALSE downloading dispatch
MOV data retained
dispatch:
RECORD.MAKE context r row s seq d data
JFALSE downloading normal
REF.WRITE cell one
XBUS.WRITE x1 context
XBUS.READ tx x1
; Keep download selected until the real read_close completes. The decoder waits for
; release, while store waits for 2; neither can consume another caller's reply.
JMP checked
normal:
XBUS.WRITE x0 context
XBUS.READ tx x0
checked:
FIELD flag tx ok
JFALSE flag done
JFALSE downloading last
MOV verified tx
JMP advance
last:
FIELD flag row l
JFALSE flag remember
MOV tx verified
done:
REF.WRITE cell finished
XBUS.WRITE x1 tx
RET
remember:
FIELD reply tx value
FIELD seq reply s
FIELD retained reply v
advance:
ADD_INTEGER i i one
JMP next
