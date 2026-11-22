; x0: byte buffer; x1: Library adapter with explicit status/busy polling.
; Each accepted write_chunk is followed by construction/transmission of the next chunk.
; Device busy is a real failure, never an automatic write retry. Tune the public driver for faster media.
; Each fragment and each poll is a real command. No retry of any mutating operation.
; Return the actual write_commit reply, not a synthesized successful write receipt.
TYPE Row RECORD o TEXT d ANY e TEXT l BOOLEAN
TYPE Context RECORD r Row s ANY d ANY
TYPE Job RECORD c Context s BOOLEAN n INTEGER
TYPE Result RECORD ok BOOLEAN value ANY
TYPE Range RECORD o INTEGER n INTEGER
TYPE Chunk RECORD offset INTEGER bytes NBT
REG context Context
REG job Job
REG row Row
REG result Result
REG range Range
REG data ANY
REG chunk Chunk
REG reply ANY
REG sequence ANY
REG total INTEGER
REG offset INTEGER
REG count INTEGER
REG remaining INTEGER
REG zero INTEGER
REG flag BOOLEAN
REG stream BOOLEAN
REG more BOOLEAN
XBUS.READ job x0
FIELD context job c
FIELD stream job s
FIELD total job n
DROP job
CONST zero 0
MOV offset zero
MOV count zero
CONST more true
JFALSE stream dispatch
CONST result {"ok":false,"value":"invalid_chunk_size"}
LESS_INTEGER flag zero chunk_bytes
JFALSE flag close
dispatch:
XBUS.WRITE x1 context
DROP context
XBUS.READ result x1
JFALSE stream response
FIELD flag result ok
JFALSE flag close
FIELD reply result value
FIELD sequence reply sequence
DROP reply
JFALSE more close
ADD_INTEGER offset offset count
LESS_INTEGER more offset total
JFALSE more commit
SUBTRACT_INTEGER remaining total offset
MIN_INTEGER count remaining chunk_bytes
request:
RECORD.MAKE range o offset n count
XBUS.WRITE x0 range
XBUS.READ chunk x0
CONST row {"o":"write_chunk","d":null,"e":"accepted","l":false}
MOV data chunk
DROP chunk
JMP build
commit:
CONST row {"o":"write_commit","d":null,"e":"accepted","l":false}
CONST data null
build:
RECORD.MAKE context r row s sequence d data
DROP data
DROP row
DROP sequence
DROP result
JMP dispatch
close:
CONST range {"o":0,"n":0}
XBUS.WRITE x0 range
response:
XBUS.WRITE x0 result
