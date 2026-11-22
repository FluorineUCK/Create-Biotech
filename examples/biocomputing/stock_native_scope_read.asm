; Selected read: scope_archive.p0=1; share x0 with the scoped checker selected by 2.
; Explicit startup read, not a memory snapshot or implicit empty ledger.
; x0 receives {v:0} and returns {ok,value}; x1 uses cluster_journal_adapter.x0.
; The editable command table positions the real drive, reads, then acknowledges its result.
TYPE Kick RECORD v INTEGER
TYPE Row RECORD o TEXT d ANY e TEXT l BOOLEAN
TYPE Context RECORD r Row s ANY d ANY
TYPE Result RECORD ok BOOLEAN value ANY
REG selection INTEGER
REG selected INTEGER
REG ready BOOLEAN
REG kick Kick
REG row Row
REG context Context
REG result Result
REG reply ANY
REG retained ANY
REG sequence ANY
REG data ANY
REG operation TEXT
REG code ANY
REG result_op TEXT
REG read_code TEXT
REG index INTEGER
REG one INTEGER
REG flag BOOLEAN
REG got BOOLEAN
CONST selected 1
select:
SLEEP selected
SIO.READ selection p0
EQUAL_INTEGER ready selection selected
JFALSE ready select
XBUS.READ kick x0
CONST index 0
CONST one 1
CONST sequence 0
CONST got false
CONST retained "missing_read_result"
CONST result_op "result"
CONST read_code "read"
next:
LIST.GET row commands index
FIELD operation row o
FIELD data row d
RECORD.MAKE context r row s sequence d data
XBUS.WRITE x1 context
XBUS.READ result x1
FIELD flag result ok
JFALSE flag done
FIELD reply result value
FIELD sequence reply sequence
VALUE.EQUALS flag operation result_op
JFALSE flag advance
FIELD code reply code
VALUE.EQUALS flag code read_code
JFALSE flag advance
FIELD retained reply value
MOV got flag
advance:
FIELD flag row l
JFALSE flag more
RECORD.MAKE result ok got value retained
JMP done
more:
ADD_INTEGER index index one
JMP next
done:
XBUS.WRITE x0 result
