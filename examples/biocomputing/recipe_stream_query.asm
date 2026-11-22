; One positioned page, received only after the reader closes its stream.
TYPE Product RECORD item TEXT count INTEGER components NBT
TYPE Products LIST Product
; This is a consumer's wire signature, not an index or proof of authority.
TYPE Page RECORD page INTEGER pages INTEGER pattern_id TEXT type TEXT priority ANY inputs ANY body ANY outputs Products fingerprint TEXT source NBT
TYPE Envelope RECORD value Page
TYPE Query RECORD q TEXT i INTEGER k TEXT
TYPE Reply RECORD c TEXT n INTEGER r Query v ANY
REG env Envelope
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
REG status INTEGER
REG ok BOOLEAN
CONST zero 0
CONST one 1
gate:
SIO.READ status p0
CONST i 15
EQUAL_INTEGER ok status i
JFALSE ok wait_gate
XBUS.READ env x0
FIELD raw env value
DROP env
JMP await_query
wait_gate:
SLEEP poll_ticks
JMP gate
await_query:
SLEEP poll_ticks
XBUS.STATUS status x1
GREATER_INTEGER ok status zero
JFALSE ok await_query
XBUS.READ req x1
FIELD needle req q
FIELD i req i
LESS_INTEGER ok i zero
JFALSE ok scan
invalid_cursor:
CONST code "invalid_cursor"
JMP no_detail
scan:
LESS_INTEGER ok i one
JFALSE ok end
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
EQUAL_INTEGER ok i one
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
