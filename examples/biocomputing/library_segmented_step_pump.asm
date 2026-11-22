; One actual Library command per managed task. The caller claims the result and
; retires this task's receipts before starting the next command. No retry.
; s is explicit software continuation {n:total (-1 idle), o:offset, q:sequence}.
TYPE Row RECORD o TEXT d ANY e TEXT l BOOLEAN
TYPE Context RECORD r Row s ANY d ANY
TYPE Job RECORD c Context s BOOLEAN n INTEGER
TYPE Result RECORD ok BOOLEAN value ANY
TYPE State RECORD n INTEGER o INTEGER q ANY
TYPE Range RECORD o INTEGER n INTEGER
TYPE Chunk RECORD offset INTEGER bytes NBT
REG context Context
REG job Job
REG result Result
REG range Range
REG chunk Chunk
REG row Row
REG data ANY
REG reply ANY
REG sequence ANY
REG total INTEGER
REG offset INTEGER
REG count INTEGER
REG remaining INTEGER
REG stream BOOLEAN
REG more BOOLEAN
REG flag BOOLEAN
CONST offset 0
CONST more true
FIELD total s n
FIELD sequence s q
LESS_INTEGER flag total offset
JFALSE flag continuation
XBUS.READ job x0
FIELD context job c
FIELD stream job s
FIELD total job n
JFALSE stream dispatch
LESS_INTEGER flag offset c
JFALSE flag invalid
JMP dispatch
continuation:
FIELD offset s o
CONST stream true
LESS_INTEGER more offset total
JFALSE more commit
SUBTRACT_INTEGER remaining total offset
MIN_INTEGER count remaining c
RECORD.MAKE range o offset n count
XBUS.WRITE x0 range
XBUS.READ chunk x0
MOV data chunk
ADD_INTEGER offset offset count
CONST row {"o":"write_chunk","d":null,"e":"accepted","l":false}
JMP build
commit:
CONST row {"o":"write_commit","d":null,"e":"accepted","l":false}
CONST data null
build:
RECORD.MAKE context r row s sequence d data
dispatch:
XBUS.WRITE x1 context
XBUS.READ result x1
JFALSE stream respond
FIELD flag result ok
JFALSE flag close
JFALSE more close
retained:
FIELD reply result value
FIELD sequence reply s
RET
invalid:
CONST result {"ok":false,"value":"invalid_chunk_size"}
close:
CONST range {"o":0,"n":0}
XBUS.WRITE x0 range
respond:
XBUS.WRITE x0 result
CONST total -1
