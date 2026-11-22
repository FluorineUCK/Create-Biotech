; Validate each requested stream before exposing its bytes; eof is advisory, range/total are authoritative.
TYPE Row RECORD o TEXT d ANY e TEXT l BOOLEAN
TYPE Context RECORD r Row s ANY d ANY
TYPE Result RECORD ok BOOLEAN value ANY
REG context Context
REG tx Result
REG flag BOOLEAN
TYPE Info RECORD bytes INTEGER format TEXT kind TEXT version ANY
TYPE MaybeInfo OPTIONAL Info
TYPE CheckedInfo RECORD ok BOOLEAN value MaybeInfo
TYPE Chunk RECORD offset INTEGER total INTEGER eof BOOLEAN bytes NBT
TYPE MaybeChunk OPTIONAL Chunk
TYPE CheckedChunk RECORD ok BOOLEAN value MaybeChunk
REG info Info
REG checkedInfo CheckedInfo
REG maybeInfo MaybeInfo
REG chunk Chunk
REG checked CheckedChunk
REG maybe MaybeChunk
REG raw ANY
REG bytes NBT
REG total INTEGER
REG offset INTEGER
REG actual INTEGER
REG zero INTEGER
REG expected INTEGER
XBUS.READ context x0
FIELD raw context d
VALUE.CHECK checkedInfo raw
FIELD maybeInfo checkedInfo value
REQUIRE_VALUE info maybeInfo
FIELD total info bytes
CONST zero 0
MOV offset zero
XBUS.WRITE x1 context
next:
XBUS.READ tx x1
FIELD flag tx ok
JFALSE flag done
FIELD raw tx value
IS_NULL flag raw
JFALSE flag part
EQUAL_INTEGER flag offset total
JFALSE flag invalid
JMP done
part:
VALUE.CHECK checked raw
FIELD maybe checked value
REQUIRE_VALUE chunk maybe
FIELD actual chunk offset
EQUAL_INTEGER flag actual offset
JFALSE flag invalid
FIELD actual chunk total
EQUAL_INTEGER flag actual total
JFALSE flag invalid
FIELD bytes chunk bytes
NBT.TAG actual bytes
CONST expected 7
EQUAL_INTEGER flag actual expected
JFALSE flag invalid
NBT.LENGTH actual bytes
LESS_INTEGER flag zero actual
JFALSE flag invalid
ADD_INTEGER offset offset actual
LESS_INTEGER flag total offset
JFALSE flag send
JMP invalid
send:
CONST flag true
RECORD.MAKE tx ok flag value bytes
XBUS.WRITE x0 tx
JMP next
invalid:
CONST tx {"ok":false,"value":"invalid_read_range"}
done:
XBUS.WRITE x0 tx
