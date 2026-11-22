; Existing mechanical command table, but stat/read always name the explicitly requested entry.
; x1: {entry}, then the original-version mutation; x0: existing cluster_journal_adapter.
; Ordinary commands still seek/select, claim and ACK results. No hidden Library access.
TYPE Row RECORD o TEXT d ANY e TEXT l BOOLEAN
TYPE Context RECORD r Row s ANY d ANY
TYPE Result RECORD ok BOOLEAN value ANY
TYPE Kick RECORD entry TEXT
TYPE Mutation RECORD op TEXT volume TEXT entry TEXT expected ANY kind TEXT value ANY
REG kick Kick
REG mutation Mutation
REG context Context
REG tx Result
REG row Row
REG i INTEGER
REG one INTEGER
REG seq ANY
REG op TEXT
REG write TEXT
REG read TEXT
REG stat TEXT
REG result TEXT
REG entry TEXT
REG actual TEXT
REG data ANY
REG reply ANY
REG retained ANY
REG flag BOOLEAN
request:
XBUS.READ kick x1
FIELD entry kick entry
CONST i 0
CONST one 1
CONST seq 0
CONST write "write"
CONST read "read"
CONST stat "stat"
CONST result "result"
CONST retained null
next:
LIST.GET row commands i
FIELD op row o
FIELD data row d
VALUE.EQUALS flag op read
JFALSE flag stat_entry
MOV data entry
JMP check_write
stat_entry:
VALUE.EQUALS flag op stat
JFALSE flag check_write
MOV data entry
check_write:
VALUE.EQUALS flag op write
JFALSE flag dispatch
RECORD.MAKE tx ok flag value retained
XBUS.WRITE x1 tx
XBUS.READ mutation x1
FIELD actual mutation entry
VALUE.EQUALS flag actual entry
JFALSE flag mismatch
MOV data mutation
dispatch:
RECORD.MAKE context r row s seq d data
XBUS.WRITE x0 context
XBUS.READ tx x0
FIELD flag tx ok
JFALSE flag done
FIELD reply tx value
FIELD seq reply sequence
VALUE.EQUALS flag op result
JFALSE flag advance
FIELD retained reply value
advance:
ADD_INTEGER i i one
FIELD flag row l
JFALSE flag next
RECORD.MAKE tx ok flag value retained
JMP done
mismatch:
CONST tx {"ok":false,"value":"entry_mismatch"}
done:
XBUS.WRITE x1 tx
JMP request
