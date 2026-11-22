; SIO p0: 0=control/accept candidate, 1=download owns x1, 2=final result for store.
; x0 retains the explicit write buffer/pump route; x1 is selected, not a hidden extra port.
TYPE Row RECORD o TEXT d ANY e TEXT l BOOLEAN
TYPE Context RECORD r Row s ANY d ANY
TYPE Result RECORD ok BOOLEAN value ANY
TYPE Header RECORD volume TEXT entry TEXT expected ANY kind TEXT format TEXT bytes INTEGER
TYPE Transfer RECORD h Header b NBT
REG mutation Transfer
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
CONST zero 0
CONST one 1
CONST finished 2
SIO.WRITE p0 zero
XBUS.READ mutation x1
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
MOV data mutation
check_download:
VALUE.EQUALS flag op download
JFALSE flag dispatch
MOV data retained
dispatch:
RECORD.MAKE context r row s seq d data
JFALSE flag normal
SIO.WRITE p0 one
XBUS.WRITE x1 context
XBUS.READ tx x1
; Keep download selected until the real read_close completes. The decoder waits for
; release, while store waits for 2; neither can consume another caller's reply.
FIELD flag tx ok
JFALSE flag done
MOV verified tx
JMP advance
normal:
XBUS.WRITE x0 context
XBUS.READ tx x0
FIELD flag tx ok
JFALSE flag done
FIELD flag row l
JFALSE flag remember
MOV tx verified
done:
SIO.WRITE p0 finished
XBUS.WRITE x1 tx
RET
remember:
FIELD reply tx value
FIELD seq reply sequence
FIELD retained reply value
advance:
ADD_INTEGER i i one
JMP next
