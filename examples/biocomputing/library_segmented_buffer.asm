; Public byte-buffer proxy: upstream coordinator x0, segmented pump x1.
; Only ordinary 'write' contexts are converted. Other commands pass through unchanged.
; Store software encodes first. A zero-count range releases this local byte snapshot.
TYPE Row RECORD o TEXT d ANY e TEXT l BOOLEAN
TYPE Context RECORD r Row s ANY d ANY
TYPE Job RECORD c Context s BOOLEAN n INTEGER
TYPE Result RECORD ok BOOLEAN value ANY
TYPE Range RECORD o INTEGER n INTEGER
TYPE Slice RECORD offset INTEGER bytes NBT
TYPE Header RECORD volume TEXT entry TEXT expected ANY kind TEXT format TEXT bytes INTEGER
TYPE Transfer RECORD h Header b NBT
TYPE MaybeTransfer OPTIONAL Transfer
TYPE CheckedTransfer RECORD ok BOOLEAN value MaybeTransfer
TYPE Integers LIST INTEGER
TYPE Maybe OPTIONAL INTEGER
TYPE Checked RECORD ok BOOLEAN value Maybe
REG context Context
REG job Job
REG row Row
REG result Result
REG range Range
REG slice Slice
REG header Header
REG raw ANY
REG transfer Transfer
REG maybeTransfer MaybeTransfer
REG checkedTransfer CheckedTransfer
REG sequence ANY
REG operation TEXT
REG write TEXT
REG bytes NBT
REG part NBT
REG empty NBT
REG start INTEGER
REG child NBT
REG checked Checked
REG optional Maybe
REG members Integers
REG length INTEGER
REG count INTEGER
REG offset INTEGER
REG end INTEGER
REG value INTEGER
REG zero INTEGER
REG one INTEGER
REG flag BOOLEAN
LIST.MAKE members
NBT.PACK empty 7 members
MOV bytes empty
CONST zero 0
CONST one 1
CONST write "write"
CONST length 0
next:
XBUS.READ context x0
FIELD row context r
FIELD operation row o
VALUE.EQUALS flag operation write
JFALSE flag send
FIELD raw context d
VALUE.CHECK checkedTransfer raw
FIELD maybeTransfer checkedTransfer value
REQUIRE_VALUE transfer maybeTransfer
FIELD bytes transfer b
FIELD header transfer h
NBT.LENGTH length bytes
FIELD sequence context s
CONST row {"o":"write_begin","d":null,"e":"receiving","l":false}
RECORD.MAKE context r row s sequence d header
DROP raw
DROP transfer
DROP maybeTransfer
DROP checkedTransfer
send:
RECORD.MAKE job c context s flag n length
XBUS.WRITE x1 job
JFALSE flag response
range:
XBUS.READ range x1
FIELD count range n
EQUAL_INTEGER flag count zero
JFALSE flag copy
MOV bytes empty
JMP response
copy:
FIELD offset range o
MOV start offset
ADD_INTEGER end offset count
LIST.MAKE members
member:
LESS_INTEGER flag offset end
JFALSE flag packed
NBT.CHILD child bytes offset
NBT.READ.INTEGER checked child
FIELD optional checked value
REQUIRE_VALUE value optional
LIST.APPEND members members value
ADD_INTEGER offset offset one
JMP member
packed:
NBT.PACK part 7 members
RECORD.MAKE slice offset start bytes part
XBUS.WRITE x1 slice
JMP range
response:
XBUS.READ result x1
XBUS.WRITE x0 result
JMP next
