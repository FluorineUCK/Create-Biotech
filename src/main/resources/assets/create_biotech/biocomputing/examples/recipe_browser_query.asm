; ROM A.x1 -> x0: receive exactly page_count authorized, successful page envelopes.
; x1 <-> terminal: {q:keyword,i:cursor,k:request}; return one match at a time.
; Immutable snapshot for this run. No automatic book reads, order, or retry.
TYPE Product RECORD item TEXT count INTEGER components NBT
TYPE Products LIST Product
; This is a consumer's wire signature, not an index or proof of authority.
TYPE Page RECORD page INTEGER pages INTEGER pattern_id TEXT type TEXT priority ANY inputs ANY body ANY outputs Products fingerprint TEXT source NBT
TYPE Envelope RECORD value Page
TYPE Pages LIST Page
TYPE Query RECORD q TEXT i INTEGER k TEXT
TYPE Reply RECORD c TEXT n INTEGER r Query v ANY
REG pages Pages
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
LESS_INTEGER ok page_count zero
JFALSE ok init
CONST err "invalid_page_count"
RET
init:
LIST.MAKE pages
MOV i zero
load:
LESS_INTEGER ok i page_count
JFALSE ok await_query
; ROM station must be running. An empty active wire blocks here; faults never trigger a retry.
XBUS.READ env x0
FIELD raw env value
DROP env
; Store the original page value, including item components, stages, source and fingerprint.
LIST.APPEND pages pages raw
DROP raw
ADD_INTEGER i i one
JMP load
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
