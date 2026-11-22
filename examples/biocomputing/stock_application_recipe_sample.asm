; Same stock A/B and Reader grant as catalogue publishing. Status then sample, never ACK.
; x0 <-> stock_application_b.x1; x1 -> recipe_selection.x0.
TYPE Target RECORD x INTEGER y INTEGER z INTEGER
TYPE Call RECORD k BOOLEAN t Target o TEXT a ANY
TYPE Response RECORD r ANY
TYPE View RECORD v ANY
TYPE Keys LIST TEXT
TYPE Sample RECORD device ANY session ANY components Keys
REG call Call
REG response Response
REG view View
REG sample Sample
REG op TEXT
REG args ANY
REG reply ANY
REG state ANY
REG device ANY
REG session ANY
REG field TEXT
REG keys Keys
REG is_reader BOOLEAN
REG sampling BOOLEAN
REG ok BOOLEAN
CONST is_reader true
CONST sampling false
CONST op "status"
CONST args {}
CONST field "state"
send:
RECORD.MAKE call k is_reader t reader o op a args
XBUS.WRITE x0 call
XBUS.READ response x0
FIELD reply response r
JFALSE sampling prepare
JMP done
prepare:
HAS.FIELD ok reply field
JFALSE ok done
FIELD state reply state
FIELD device state device
FIELD session state session
CONST keys ["create_biotech:stock"]
RECORD.MAKE sample device device session session components keys
MOV args sample
CONST sampling true
CONST op "sample"
JMP send
done:
RECORD.MAKE view v reply
XBUS.WRITE x1 view
