; Never allocate a new correlation as a BUSINESS name. One standard materials operation per stock device/sequence.
; Source is the public frozen queue head, not a rebindable session. Port authorizes the physical sender.
TYPE State RECORD device TEXT session TEXT revision INTEGER inputs INTEGER
TYPE Head RECORD sequence INTEGER kind TEXT player TEXT address TEXT items ANY
TYPE Source RECORD state State head Head
TYPE Selected RECORD s Source a TEXT i ANY
TYPE Shipment RECORD j TEXT a TEXT i ANY
TYPE Digits LIST TEXT
REG input Selected
REG source Source
REG state State
REG head Head
REG request Shipment
REG reply ANY
REG job TEXT
REG digits Digits
REG prefix TEXT
REG tail TEXT
REG digit TEXT
REG device TEXT
REG address TEXT
REG items ANY
REG seq INTEGER
REG index INTEGER
REG ten INTEGER
REG zero INTEGER
REG one INTEGER
REG ok BOOLEAN
CONST zero 0
CONST one 1
CONST ten 10
CONST tail ""
CONST prefix "/materials/"
CONST digits ["0","1","2","3","4","5","6","7","8","9"]
wait:
SLEEP one
SIO.READ index p0
EQUAL_INTEGER ok index one
JFALSE ok wait
XBUS.READ input x0
FIELD source input s
FIELD head source head
FIELD state source state
FIELD device state device
FIELD seq head sequence
GREATER_INTEGER ok seq zero
CONST reply {"code":"invalid_source"}
JFALSE ok done
format:
REMAINDER_INTEGER index seq ten
LIST.GET digit digits index
TEXT.CONCAT tail digit tail
DIVIDE_INTEGER seq seq ten
GREATER_INTEGER ok seq zero
JFALSE ok emit
JMP format
emit:
TEXT.CONCAT job device prefix
TEXT.CONCAT job job tail
FIELD address input a
FIELD items input i
RECORD.MAKE request j job a address i items
XBUS.WRITE x1 request
MOV reply job
done:
