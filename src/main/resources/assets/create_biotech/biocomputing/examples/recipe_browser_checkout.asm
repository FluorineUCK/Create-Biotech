TYPE Ref RECORD k TEXT page INTEGER fingerprint TEXT source NBT
TYPE Pick RECORD s TEXT d Ref b TEXT o ANY
TYPE Order RECORD s TEXT d Ref n INTEGER
TYPE Draft RECORD request Order c TEXT o ANY
REG pick Pick
REG draft Draft
REG order Order
REG ref Ref
REG j TEXT
REG products ANY
REG text TEXT
REG c TEXT
REG ready BOOLEAN
REG cursor INTEGER
REG value INTEGER
REG point TEXT_NEXT
REG cp INTEGER
REG z INTEGER
REG ascii INTEGER
REG ten INTEGER
REG max INTEGER
REG ok BOOLEAN
CONST z 0
CONST ascii 48
CONST ten 10
CONST max 2147483647
CONST c "invalid_batches"
CONST ready false
MOV cursor z
MOV value z
; After wiring and resuming the peer, this one-shot desk waits for one proposal.
XBUS.READ pick x0
FIELD text pick b
parse:
TEXT.NEXT point text cursor
FIELD ok point done
JFALSE ok digit
GREATER_INTEGER ready value z
JFALSE ready reject
CONST c "staged"
JMP reply
digit:
FIELD cursor point next
FIELD cp point codepoint
SUBTRACT_INTEGER cp cp ascii
LESS_EQUAL_INTEGER ok z cp
JFALSE ok reject
LESS_INTEGER ok cp ten
JFALSE ok reject
MULTIPLY_INTEGER value value ten
ADD_INTEGER value value cp
GREATER_INTEGER ok value max
JFALSE ok parse
reject:
MOV value z
reply:
FIELD j pick s
FIELD ref pick d
FIELD products pick o
RECORD.MAKE order s j d ref n value
RECORD.MAKE draft request order c c o products
