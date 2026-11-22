; Bounded software ledger. Only this service task owns its private entries.
; x0 receives {d: stock device, h: complete native head}; replies {c,j}.
; Persist the new entry BEFORE sending j. Repeated source identity never allocates again.
; Ordinary saves keep this RAM; explicit reset/reinstall erases it. Not a disk or ACK.
TYPE Head RECORD kind TEXT sequence INTEGER player TEXT address TEXT order NBT
TYPE Input RECORD d TEXT h Head
TYPE Entry RECORD s Input j TEXT
TYPE Entries LIST Entry
TYPE Reply RECORD c TEXT j TEXT
REG input Input
REG bound Input
REG head Head
REG previous Head
REG entries Entries
REG entry Entry
REG reply Reply
REG device TEXT
REG other_device TEXT
REG kind TEXT
REG native_kind TEXT
REG job TEXT
REG code TEXT
REG sequence INTEGER
REG other_sequence INTEGER
REG index INTEGER
REG length INTEGER
REG status INTEGER
REG zero INTEGER
REG one INTEGER
REG ok BOOLEAN
CONST zero 0
CONST one 1
CONST native_kind "native_order"
CONST entries []
next:
SLEEP one
XBUS.STATUS status x0
GREATER_INTEGER ok status zero
JFALSE ok next
XBUS.READ input x0
CONST job ""
CONST code "invalid_source"
FIELD device input d
TEXT.LENGTH length device
GREATER_INTEGER ok length zero
JFALSE ok emit
FIELD head input h
FIELD kind head kind
VALUE.EQUALS ok kind native_kind
JFALSE ok emit
FIELD sequence head sequence
GREATER_INTEGER ok sequence zero
JFALSE ok emit
LIST.LENGTH length entries
MOV index zero
scan:
LESS_INTEGER ok index length
JFALSE ok create
LIST.GET entry entries index
ADD_INTEGER index index one
FIELD bound entry s
FIELD other_device bound d
VALUE.EQUALS ok device other_device
JFALSE ok scan
FIELD previous bound h
FIELD other_sequence previous sequence
EQUAL_INTEGER ok sequence other_sequence
JFALSE ok scan
CONST code "source_conflict"
VALUE.EQUALS ok input bound
JFALSE ok emit
FIELD job entry j
CONST code "existing"
JMP emit
create:
CONST code "ledger_full"
LESS_INTEGER ok length capacity
JFALSE ok emit
CORRELATION job
RECORD.MAKE entry s input j job
LIST.APPEND entries entries entry
CONST code "bound"
emit:
RECORD.MAKE reply c code j job
XBUS.WRITE x0 reply
JMP next
