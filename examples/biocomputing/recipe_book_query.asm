; x0 is dedicated to the gated forwarder. A null page seals the scan with its error (empty = success).
TYPE Product RECORD item TEXT count INTEGER components NBT
TYPE Products LIST Product
; This is a consumer's wire signature, not an index or proof of authority.
TYPE Page RECORD page INTEGER pages INTEGER pattern_id TEXT type TEXT priority ANY inputs ANY body ANY outputs Products fingerprint TEXT source NBT
TYPE Maybe OPTIONAL Page
TYPE Envelope RECORD c TEXT v Maybe
TYPE Pages LIST Page
TYPE Query RECORD q TEXT i INTEGER k TEXT
TYPE Reply RECORD c TEXT n INTEGER r Query v ANY
REG pages Pages
REG env Envelope
REG maybe Maybe
REG page_count INTEGER
REG empty TEXT
REG req Query
REG reply Reply
REG raw Page
REG detail ANY
REG value Product
REG outputs Products
REG name TEXT
REG needle TEXT
REG code TEXT
REG err TEXT
REG i INTEGER
REG j INTEGER
REG n INTEGER
REG zero INTEGER
REG one INTEGER
REG ok BOOLEAN
CONST zero 0
CONST one 1
CONST empty ""
LIST.MAKE pages
load:
XBUS.READ env x0
FIELD maybe env v
IS_NULL ok maybe
JFALSE ok append
FIELD err env c
DROP env
LIST.LENGTH page_count pages
JMP await_query
append:
REQUIRE_VALUE raw maybe
DROP env
DROP maybe
LIST.APPEND pages pages raw
DROP raw
JMP load
await_query:
XBUS.READ req x1
FIELD needle req q
FIELD i req i
MOV code err
VALUE.EQUALS ok code empty
JFALSE ok no_detail
LESS_INTEGER ok i zero
JFALSE ok scan
invalid_cursor:
CONST code "invalid_cursor"
JMP no_detail
scan:
LESS_INTEGER ok i page_count
JFALSE ok end
LIST.GET raw pages i
ADD_INTEGER i i one
FIELD name raw pattern_id
FIELD outputs raw outputs
LIST.LENGTH n outputs
MOV j zero
test_name:
TEXT.CONTAINS.CI ok name needle
JFALSE ok next_name
MOV detail raw
CONST code "match"
JMP send
next_name:
LESS_INTEGER ok j n
JFALSE ok scan
LIST.GET value outputs j
FIELD name value item
ADD_INTEGER j j one
JMP test_name
end:
EQUAL_INTEGER ok i page_count
JFALSE ok invalid_cursor
CONST code "end"
no_detail:
CONST detail null
send:
RECORD.MAKE reply c code n i r req v detail
XBUS.WRITE x1 reply
DROP reply
DROP detail
JMP await_query
