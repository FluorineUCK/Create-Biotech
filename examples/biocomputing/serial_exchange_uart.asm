; Partner x0 -> controller x1. Only this Computer owns the physical s0.
REG input ANY
REG tag TEXT
REG ok BOOLEAN
REG tx SERIAL_SEND
REG rx SERIAL_RECEIVE
REG one INTEGER
REG status INTEGER
CONST one 1
CONST tag "kind"
next:
SLEEP one
XBUS.STATUS status x0
LESS_INTEGER ok one status
JFALSE ok next
XBUS.READ input x0
HAS.FIELD ok input tag
JFALSE ok poll
SERIAL.SEND s0 input tx
XBUS.WRITE x0 tx
JMP next
poll:
SERIAL.RECEIVE rx s0
FIELD ok rx ok
JFALSE ok no_reply
emit:
XBUS.WRITE x0 rx
JMP next
no_reply:
; This private polling interface yields an empty candidate, never a service reply.
CONST rx {"ok":false,"status":"no_reply","value":{}}
JMP emit
