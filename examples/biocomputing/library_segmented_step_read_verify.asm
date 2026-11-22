; Validate each requested stream before exposing its bytes; eof is advisory, range/total are authoritative.
TYPE Row RECORD o TEXT d ANY e TEXT l BOOLEAN
TYPE Info RECORD bytes INTEGER format TEXT kind TEXT version ANY
TYPE Context RECORD r Row s ANY d Info
TYPE Result RECORD ok BOOLEAN value ANY
REG context Context
REG tx Result
REG flag BOOLEAN
TYPE MaybeInfo OPTIONAL Info
TYPE CheckedInfo RECORD ok BOOLEAN value MaybeInfo
TYPE Chunk RECORD o INTEGER n INTEGER e BOOLEAN b NBT
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
CONST zero 0
FIELD total s n
FIELD offset s o
LESS_INTEGER flag total zero
JFALSE flag next
XBUS.READ context x0
FIELD info context d
FIELD total info bytes
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
FIELD raw raw value
VALUE.CHECK checked raw
FIELD maybe checked value
REQUIRE_VALUE chunk maybe
FIELD actual chunk o
EQUAL_INTEGER flag actual offset
JFALSE flag invalid
FIELD actual chunk n
EQUAL_INTEGER flag actual total
JFALSE flag invalid
FIELD bytes chunk b
NBT.TAG actual bytes
CONST expected 7
EQUAL_INTEGER flag actual expected
JFALSE flag invalid
NBT.LENGTH actual bytes
LESS_INTEGER flag zero actual
JFALSE flag invalid
ADD_INTEGER offset offset actual
LESS_EQUAL_INTEGER flag offset total
JFALSE flag invalid
send:
RECORD.MAKE tx ok flag value bytes
JMP publish
invalid:
CONST tx {"ok":false,"value":"invalid_read_range"}
done:
CONST total -1
publish:
XBUS.WRITE x0 tx
