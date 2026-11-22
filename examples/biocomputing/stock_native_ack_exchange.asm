; Recovery packet encoder. x0 -> ack_current.x1; x1 -> serial_exchange.x0.
; Each stage waits for its caller. No retry, no hidden receiver or stock operation.
TYPE Target RECORD x INTEGER y INTEGER z INTEGER
TYPE Command RECORD a ANY ok BOOLEAN
TYPE Packet RECORD kind TEXT version INTEGER target Target request TEXT operation TEXT arguments ANY
TYPE Call RECORD p Packet e TEXT
TYPE Reply RECORD r ANY
REG input Command
REG args ANY
REG packet Packet
REG call Call
REG received Reply
REG kind TEXT
REG family TEXT
REG operation TEXT
REG request TEXT
REG one INTEGER
REG status INTEGER
REG ok BOOLEAN
REG ready BOOLEAN
REG acking BOOLEAN
CONST kind "stock_application_command"
CONST family "stock_application_reply"
CONST one 1
CONST operation "status"
CONST acking false
next:
; The peer may still be paused during installation. Status probes do not receive data.
SLEEP poll_ticks
XBUS.STATUS status x0
LESS_INTEGER ready one status
JFALSE ready next
XBUS.READ input x0
FIELD ok input ok
JFALSE ok done
FIELD args input a
CORRELATION request
RECORD.MAKE packet kind kind version one target output request request operation operation arguments args
RECORD.MAKE call p packet e family
XBUS.WRITE x1 call
XBUS.READ received x1
XBUS.WRITE x0 received
JFALSE acking prepare_ack
JMP done
prepare_ack:
CONST acking true
CONST operation "ack"
JMP next
done:
