; x0: {n: captured native order}; x1: explicit link to stock_native_item x0.
; Projects ordered_stacks only, NOT crafting/dispatch/acknowledgement.
; Caller retains the original order and its ordered_crafts context.
; Publish the complete list once; an invalid later row never exposes a partial list.
TYPE Request RECORD n NBT
TYPE Path LIST TEXT
TYPE Item RECORD item TEXT count INTEGER components NBT
TYPE MaybeItem OPTIONAL Item
TYPE RowReply RECORD c TEXT i MaybeItem
TYPE Items LIST Item
TYPE MaybeItems OPTIONAL Items
TYPE Reply RECORD c TEXT i MaybeItems
REG request Request
REG current NBT
REG row NBT
REG path Path
REG wanted TEXT
REG key TEXT
REG accepted TEXT
REG code TEXT
REG result Reply
REG received RowReply
REG optional MaybeItem
REG item Item
REG out Items
REG depth INTEGER
REG levels INTEGER
REG n INTEGER
REG index INTEGER
REG length INTEGER
REG zero INTEGER
REG one INTEGER
REG tag INTEGER
REG list_tag INTEGER
REG ok BOOLEAN
CONST zero 0
CONST one 1
CONST list_tag 9
CONST accepted "ok"
CONST path ["ordered_stacks","entries"]
LIST.LENGTH levels path
next:
SLEEP one
XBUS.STATUS n x0
LESS_INTEGER ok one n
JFALSE ok next
XBUS.READ request x0
FIELD current request n
CONST result {"c":"invalid_order","i":null}
CONST out []
MOV depth zero
find:
LIST.GET wanted path depth
NBT.LENGTH n current
scan:
GREATER_INTEGER ok n zero
JFALSE ok reply
SUBTRACT_INTEGER n n one
NBT.KEY key current n
VALUE.EQUALS ok key wanted
JFALSE ok scan
NBT.CHILD current current n
ADD_INTEGER depth depth one
LESS_INTEGER ok depth levels
JFALSE ok rows
JMP find
rows:
NBT.TAG tag current
EQUAL_INTEGER ok tag list_tag
JFALSE ok reply
NBT.LENGTH length current
GREATER_INTEGER ok length zero
JFALSE ok reply
MOV index zero
row_loop:
LESS_INTEGER ok index length
JFALSE ok complete
NBT.CHILD row current index
RECORD.MAKE request n row
XBUS.WRITE x1 request
XBUS.READ received x1
FIELD code received c
VALUE.EQUALS ok code accepted
JFALSE ok invalid
FIELD optional received i
REQUIRE_VALUE item optional
LIST.APPEND out out item
ADD_INTEGER index index one
JMP row_loop
complete:
RECORD.MAKE result c accepted i out
reply:
XBUS.WRITE x0 result
JMP next
invalid:
CONST result {"c":"invalid_material","i":null}
JMP reply
