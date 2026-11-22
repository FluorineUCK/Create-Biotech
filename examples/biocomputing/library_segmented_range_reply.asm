; Explicit software projection AFTER library_service_b/c correlate the real frame
; and read_adapter checks the expected business code. This is not a wire protocol.
; Requests and failed outcomes are unchanged; successful values are never truncated.
; Only redundant transport metadata is omitted from the caller's copied receipt.
TYPE Row RECORD o TEXT d ANY e TEXT l BOOLEAN
TYPE Context RECORD r Row s ANY d ANY
TYPE Result RECORD ok BOOLEAN value ANY
TYPE Reply RECORD sequence ANY s ANY code ANY value ANY
TYPE Short RECORD o ANY n ANY e ANY b ANY
REG short Short
REG offset ANY
REG total ANY
REG eof ANY
REG bytes ANY
REG expected TEXT
REG range BOOLEAN
REG context Context
REG result Result
REG raw ANY
REG sequence ANY
REG code ANY
REG value ANY
REG reply Reply
REG ok BOOLEAN
XBUS.READ context x0
XBUS.WRITE x1 context
DROP context
XBUS.READ result x1
FIELD ok result ok
JFALSE ok done
FIELD raw result value
FIELD sequence raw sequence
FIELD code raw code
FIELD value raw value
CONST expected "read_chunk"
VALUE.EQUALS range code expected
JFALSE range project
FIELD offset value offset
FIELD total value total
FIELD eof value eof
FIELD bytes value bytes
RECORD.MAKE short o offset n total e eof b bytes
MOV value short
project:
RECORD.MAKE reply sequence sequence s sequence code code value value
RECORD.MAKE result ok ok value reply
done:
XBUS.WRITE x0 result
