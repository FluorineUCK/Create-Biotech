; Accept only an already assembled material list from M during its explicit
; SIO output phase. Keep the confirmed browser job, not a fake stock queue ID.
TYPE Selected RECORD s TEXT a TEXT i ANY
TYPE Shipment RECORD j TEXT a TEXT i ANY
REG result Shipment
REG picked Selected
REG id TEXT
REG address TEXT
REG items ANY
REG level INTEGER
REG one INTEGER
REG ok BOOLEAN
CONST one 1
wait:
SLEEP one
SIO.READ level p0
EQUAL_INTEGER ok level one
JFALSE ok wait
XBUS.READ picked x0
FIELD id picked s
FIELD address picked a
FIELD items picked i
RECORD.MAKE result j id a address i items
XBUS.WRITE x1 result
