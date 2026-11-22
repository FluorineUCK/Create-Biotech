; x0 receives the compact request from stock_native_archive_check: {s:source,e:entries,v:version}.
; x1 asks stock_native_archive_store to persist a new candidate with the ORIGINAL version.
; A newly allocated job is never returned before that archive has succeeded. No automatic retry.
TYPE Head RECORD kind TEXT sequence INTEGER player TEXT address TEXT order NBT
TYPE Input RECORD d TEXT h Head
TYPE Entry RECORD s Input j TEXT
TYPE Entries LIST Entry
TYPE Version RECORD medium TEXT entry TEXT revision INTEGER
TYPE Snapshot RECORD kind TEXT value Entries version Version
TYPE Request RECORD s Input e Entries v Version
TYPE Reply RECORD c TEXT j TEXT
TYPE Archive RECORD v ANY expected ANY
TYPE Result RECORD ok BOOLEAN value ANY
REG request Request
REG source Input
REG bound Input
REG entries Entries
REG entry Entry
REG head Head
REG previous Head
REG archive Archive
REG result Result
REG version Version
REG reply Reply
REG device TEXT
REG other TEXT
REG code TEXT
REG job TEXT
REG candidate TEXT
REG sequence INTEGER
REG other_sequence INTEGER
REG index INTEGER
REG length INTEGER
REG zero INTEGER
REG one INTEGER
REG flag BOOLEAN
CONST zero 0
CONST one 1
CONST job ""
XBUS.READ request x0
FIELD source request s
FIELD device source d
FIELD head source h
FIELD sequence head sequence
FIELD entries request e
LIST.LENGTH length entries
MOV index zero
scan:
LESS_INTEGER flag index length
JFALSE flag create
LIST.GET entry entries index
ADD_INTEGER index index one
FIELD bound entry s
FIELD other bound d
VALUE.EQUALS flag device other
JFALSE flag scan
FIELD previous bound h
FIELD other_sequence previous sequence
EQUAL_INTEGER flag sequence other_sequence
JFALSE flag scan
CONST code "source_conflict"
VALUE.EQUALS flag source bound
JFALSE flag emit
FIELD candidate entry j
TEXT.LENGTH length candidate
GREATER_INTEGER flag length zero
JFALSE flag emit
MOV job candidate
CONST code "existing"
JMP emit
create:
CONST code "ledger_full"
LESS_INTEGER flag length capacity
JFALSE flag emit
CORRELATION candidate
RECORD.MAKE entry s source j candidate
LIST.APPEND entries entries entry
FIELD version request v
RECORD.MAKE archive v entries expected version
XBUS.WRITE x1 archive
XBUS.READ result x1
FIELD flag result ok
CONST code "archive_failed"
JFALSE flag emit
MOV job candidate
CONST code "bound"
emit:
RECORD.MAKE reply c code j job
XBUS.WRITE x0 reply
