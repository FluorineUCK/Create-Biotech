; x0 accepts only a successful, typed Library snapshot and the complete original source.
; Validate before passing compact local fields to archived_identity on x1. No storage authority is added.
TYPE Head RECORD kind TEXT sequence INTEGER player TEXT address TEXT order NBT
TYPE Input RECORD d TEXT h Head
TYPE Entry RECORD s Input j TEXT
TYPE Entries LIST Entry
TYPE Version RECORD medium TEXT entry TEXT revision INTEGER
TYPE Snapshot RECORD kind TEXT value Entries version Version
TYPE Request RECORD s Input book Snapshot
TYPE Checked RECORD s Input e Entries v Version
TYPE Reply RECORD c TEXT j TEXT
REG request Request
REG checked Checked
REG source Input
REG book Snapshot
REG head Head
REG entries Entries
REG version Version
REG reply Reply
REG text TEXT
REG expected TEXT
REG number INTEGER
REG zero INTEGER
REG flag BOOLEAN
CONST zero 0
CONST reply {"c":"invalid_source","j":""}
XBUS.READ request x0
FIELD source request s
FIELD text source d
TEXT.LENGTH number text
GREATER_INTEGER flag number zero
JFALSE flag emit
FIELD head source h
FIELD text head kind
CONST expected "native_order"
VALUE.EQUALS flag text expected
JFALSE flag emit
FIELD number head sequence
GREATER_INTEGER flag number zero
JFALSE flag emit
CONST reply {"c":"invalid_archive","j":""}
FIELD book request book
FIELD text book kind
CONST expected "DATA"
VALUE.EQUALS flag text expected
JFALSE flag emit
FIELD version book version
FIELD text version medium
TEXT.LENGTH number text
GREATER_INTEGER flag number zero
JFALSE flag emit
FIELD text version entry
TEXT.LENGTH number text
GREATER_INTEGER flag number zero
JFALSE flag emit
FIELD number version revision
LESS_INTEGER flag number zero
JFALSE flag valid
JMP emit
valid:
FIELD entries book value
RECORD.MAKE checked s source e entries v version
XBUS.WRITE x1 checked
XBUS.READ reply x1
emit:
XBUS.WRITE x0 reply
