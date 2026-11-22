; One finite task per range. Explicit continuation retains bytes, not range receipts.
; Slices are computed by ordinary NBT instructions; no unmetered device-side split.
TYPE Row RECORD o TEXT d ANY e TEXT l BOOLEAN
TYPE Context RECORD r Row s ANY d ANY
TYPE Input RECORD c Context s BOOLEAN n INTEGER b NBT
TYPE Job RECORD c Context s BOOLEAN n INTEGER
TYPE Result RECORD ok BOOLEAN value ANY
TYPE Range RECORD o INTEGER n INTEGER
TYPE Slice RECORD offset INTEGER bytes NBT
TYPE Integers LIST INTEGER
TYPE Maybe OPTIONAL INTEGER
TYPE Checked RECORD ok BOOLEAN value Maybe
REG phase INTEGER
REG input Input
REG context Context
REG job Job
REG result Result
REG range Range
REG slice Slice
REG bytes NBT
REG part NBT
REG child NBT
REG checked Checked
REG optional Maybe
REG members Integers
REG length INTEGER
REG count INTEGER
REG offset INTEGER
REG start INTEGER
REG end INTEGER
REG value INTEGER
REG zero INTEGER
REG one INTEGER
REG stream BOOLEAN
REG flag BOOLEAN
CONST zero 0
CONST one 1
FIELD phase s n
FIELD bytes s b
DROP s
EQUAL_INTEGER flag phase zero
JFALSE flag range
XBUS.READ input x0
FIELD context input c
FIELD stream input s
FIELD length input n
FIELD bytes input b
DROP input
RECORD.MAKE job c context s stream n length
DROP context
XBUS.WRITE x1 job
DROP job
JFALSE stream response
CONST phase 1
RET
range:
XBUS.READ range x1
FIELD count range n
EQUAL_INTEGER flag count zero
JFALSE flag copy

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
RET
response:
XBUS.READ result x1
XBUS.WRITE x0 result
CONST phase 0
LIST.MAKE members
NBT.PACK bytes 7 members
