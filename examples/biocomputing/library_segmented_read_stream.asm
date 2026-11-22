; Dedicated download branch requests byte ranges; control commands use the separate adapter path.
; Every range is a real correlated frame; no implicit splitting or retries.
TYPE Row RECORD o TEXT d ANY e TEXT l BOOLEAN
TYPE Context RECORD r Row s ANY d ANY
TYPE Result RECORD ok BOOLEAN value ANY
REG context Context
REG row Row
REG tx Result
REG op TEXT
REG download TEXT
REG flag BOOLEAN
TYPE Info RECORD bytes INTEGER format TEXT kind TEXT version ANY
TYPE MaybeInfo OPTIONAL Info
TYPE CheckedInfo RECORD ok BOOLEAN value MaybeInfo
TYPE Chunk RECORD offset INTEGER total INTEGER eof BOOLEAN bytes NBT
TYPE MaybeChunk OPTIONAL Chunk
TYPE CheckedChunk RECORD ok BOOLEAN value MaybeChunk
TYPE Range RECORD offset INTEGER count INTEGER
REG info Info
REG checkedInfo CheckedInfo
REG maybeInfo MaybeInfo
REG chunk Chunk
REG checked CheckedChunk
REG maybe MaybeChunk
REG range Range
REG raw ANY
REG reply ANY
REG seq ANY
REG bytes NBT
REG format TEXT
REG expected TEXT
REG total INTEGER
REG offset INTEGER
REG count INTEGER
REG remaining INTEGER
REG zero INTEGER
REG eof BOOLEAN
XBUS.READ context x0
FIELD seq context s
FIELD raw context d
VALUE.CHECK checkedInfo raw
FIELD maybeInfo checkedInfo value
REQUIRE_VALUE info maybeInfo
FIELD total info bytes
FIELD format info format
CONST expected "typed_record_v1"
VALUE.EQUALS flag format expected
JFALSE flag invalid
CONST zero 0
LESS_INTEGER flag zero chunk_bytes
JFALSE flag invalid
MOV offset zero
next:
LESS_INTEGER flag offset total
JFALSE flag complete
SUBTRACT_INTEGER remaining total offset
MIN_INTEGER count remaining chunk_bytes
range:
RECORD.MAKE range offset offset count count
CONST row {"o":"read_chunk","d":null,"e":"read_chunk","l":false}
RECORD.MAKE context r row s seq d range
XBUS.WRITE x1 context
XBUS.READ tx x1
FIELD flag tx ok
JFALSE flag done
FIELD reply tx value
FIELD raw reply value
RECORD.MAKE tx ok flag value raw
XBUS.WRITE x0 tx
ADD_INTEGER offset offset count
JMP next
complete:
CONST tx {"ok":true,"value":null}
JMP done
invalid:
CONST tx {"ok":false,"value":"invalid_read_range"}
done:
XBUS.WRITE x0 tx
