; M.x0 receives a checked standard recipe, then publishes its COMPLETE exact list there.
; M.x1 uses the public material_selection_service. M.p0=1 selects the output listener.
TYPE MaybeText OPTIONAL TEXT
TYPE Row RECORD item MaybeText tag MaybeText count INTEGER components NBT
TYPE Rows LIST Row
TYPE Tags LIST TEXT
TYPE Choice RECORD slot INTEGER item TEXT tags Tags
TYPE Option OPTIONAL Choice
TYPE Exact RECORD item TEXT count INTEGER components NBT
TYPE Items LIST Exact
TYPE MaybeItem OPTIONAL Exact
TYPE Request RECORD i MaybeText t MaybeText n INTEGER c NBT p Option
TYPE Answer RECORD c TEXT i MaybeItem
TYPE Work RECORD s ANY a TEXT n INTEGER m Rows
TYPE Result RECORD s ANY a TEXT i Items
TYPE Rejected RECORD code TEXT
REG reply Rejected
REG work Work
REG result Result
REG request Request
REG answer Answer
REG source ANY
REG address TEXT
REG rows Rows
REG row Row
REG option Option
REG item MaybeText
REG tag MaybeText
REG patch NBT
REG exact Exact
REG opt MaybeItem
REG out Items
REG code TEXT
REG accepted TEXT
REG ok BOOLEAN
REG chosen BOOLEAN
REG batches INTEGER
REG count INTEGER
REG size INTEGER
REG length INTEGER
REG i INTEGER
REG one INTEGER
REG zero INTEGER
CONST i 0
CONST zero 0
CONST one 1
CONST accepted "ok"
CONST option null
LIST.MAKE out
XBUS.READ work x0
FIELD batches work n
FIELD rows work m
LIST.LENGTH size rows
GREATER_INTEGER ok size zero
CONST code "count_mismatch"
JFALSE ok reject
LIST.LENGTH length choices
GREATER_INTEGER chosen length zero
JFALSE chosen loop
EQUAL_INTEGER ok length size
JFALSE ok reject
loop:
LIST.GET row rows i
JFALSE chosen build
LIST.GET option choices i
build:
FIELD item row item
FIELD tag row tag
FIELD count row count
MULTIPLY_INTEGER count count batches
FIELD patch row components
RECORD.MAKE request i item t tag n count c patch p option
XBUS.WRITE x1 request
XBUS.READ answer x1
FIELD code answer c
VALUE.EQUALS ok code accepted
JFALSE ok reject
FIELD opt answer i
REQUIRE_VALUE exact opt
LIST.APPEND out out exact
ADD_INTEGER i i one
LESS_INTEGER ok i size
JFALSE ok emit
JMP loop
emit:
FIELD source work s
FIELD address work a
RECORD.MAKE result s source a address i out
SIO.WRITE p0 one
XBUS.WRITE x0 result
SIO.WRITE p0 zero
reject:
RECORD.MAKE reply code code
