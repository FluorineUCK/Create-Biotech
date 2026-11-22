; x0: {n: Create BigItemStack native compound} -> {c, i: optional exact material}.
; Outer count is the requested count. ItemStack.count is deliberately NOT used.
; Component patches (including removed-component keys) are copied as native data.
; Input is the captured Create row schema: exactly count and item_stack.
TYPE MaybeInteger OPTIONAL INTEGER
TYPE IntegerRead RECORD ok BOOLEAN value MaybeInteger
TYPE MaybeText OPTIONAL TEXT
TYPE TextRead RECORD ok BOOLEAN value MaybeText
TYPE Pair RECORD key TEXT value NBT
TYPE Pairs LIST Pair
TYPE Item RECORD item TEXT count INTEGER components NBT
TYPE MaybeItem OPTIONAL Item
TYPE Reply RECORD c TEXT i MaybeItem
TYPE Request RECORD n NBT
REG request Request
REG row NBT
REG stack NBT
REG child NBT
REG components NBT
REG empty NBT
REG pairs Pairs
REG result Reply
REG item Item
REG id TEXT
REG key TEXT
REG name_id TEXT
REG name_components TEXT
REG code TEXT
REG read_integer IntegerRead
REG read_text TextRead
REG optional_integer MaybeInteger
REG optional_text MaybeText
REG count INTEGER
REG n INTEGER
REG tag INTEGER
REG zero INTEGER
REG one INTEGER
REG int_tag INTEGER
REG compound_tag INTEGER
REG ok BOOLEAN
CONST zero 0
CONST one 1
CONST int_tag 3
CONST compound_tag 10
CONST name_id "id"
CONST name_components "components"
CONST pairs []
NBT.PACK empty 10 pairs
next:
XBUS.STATUS n x0
LESS_INTEGER ok one n
JFALSE ok next
XBUS.READ request x0
FIELD row request n
CONST result {"c":"invalid_material","i":null}
MOV stack empty
MOV components empty
CONST id ""
MOV count zero
NBT.LENGTH n row
row_loop:
GREATER_INTEGER ok n zero
JFALSE ok stack_begin
SUBTRACT_INTEGER n n one
NBT.CHILD child row n
; The admitted two-field row has an INT count and COMPOUND item_stack.
NBT.TAG tag child
EQUAL_INTEGER ok tag int_tag
JFALSE ok stack_field
NBT.READ.INTEGER read_integer child
FIELD optional_integer read_integer value
REQUIRE_VALUE count optional_integer
JMP row_loop
stack_field:
EQUAL_INTEGER ok tag compound_tag
JFALSE ok reply
MOV stack child
JMP row_loop
stack_begin:
NBT.LENGTH n stack
stack_loop:
GREATER_INTEGER ok n zero
JFALSE ok finish
SUBTRACT_INTEGER n n one
NBT.KEY key stack n
NBT.CHILD child stack n
VALUE.EQUALS ok key name_id
JFALSE ok component_field
NBT.READ.TEXT read_text child
FIELD optional_text read_text value
REQUIRE_VALUE id optional_text
component_field:
VALUE.EQUALS ok key name_components
JFALSE ok stack_loop
NBT.TAG tag child
EQUAL_INTEGER ok tag compound_tag
JFALSE ok reply
MOV components child
JMP stack_loop
finish:
TEXT.LENGTH n id
; A native INT count times a Java text length fits the 64-bit INTEGER.
; Positive product requires both a positive count and a nonempty identifier.
MULTIPLY_INTEGER n n count
GREATER_INTEGER ok n zero
JFALSE ok reply
RECORD.MAKE item item id count count components components
CONST code "ok"
RECORD.MAKE result c code i item
reply:
XBUS.WRITE x0 result
JMP next
