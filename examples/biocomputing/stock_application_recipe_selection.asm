; Select one frozen row from the physical reader reply. Never ACK, poll or dispatch here.
TYPE In RECORD v ANY
TYPE Order RECORD k ANY d ANY n ANY s ANY
REG in In
REG order Order
REG reply ANY
REG v ANY
REG e ANY
REG k ANY
REG data ANY
REG n ANY
REG name TEXT
REG ok BOOLEAN
REG i INTEGER
REG zero INTEGER
REG one INTEGER
CONST zero 0
CONST one 1
XBUS.READ in x0
FIELD reply in v
CONST name "value"
HAS.FIELD ok reply name
JFALSE ok done
FIELD v reply value
FIELD v v components
FIELD reply v create_biotech:stock
IS_ERROR ok reply
JFALSE ok inspect
RET
inspect:
CONST name "head"
HAS.FIELD ok reply name
JFALSE ok empty
FIELD v reply head
CONST name "items"
HAS.FIELD ok v name
JFALSE ok native_order
FIELD v v items
LIST.LENGTH i v
EQUAL_INTEGER ok i one
JFALSE ok multiple
LIST.GET v v zero
FIELD n v quantity
FIELD e v entry
FIELD k e key
FIELD data e data
RECORD.MAKE order k k d data n n s reply
XBUS.WRITE x1 order
MOV reply order
RET
empty:
CONST reply {"code":"empty"}
RET
native_order:
CONST reply {"code":"not_catalog_order"}
RET
multiple:
CONST reply {"code":"select_one_recipe"}
done:
