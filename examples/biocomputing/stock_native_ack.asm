; The preceding public guard verifies delivery. Encode one fenced ACK; never retry it.
; x0 connects stock_native_guard.x1; x1 connects serial_exchange.x0.
TYPE Target RECORD x INTEGER y INTEGER z INTEGER
TYPE Verified RECORD s ANY j TEXT r ANY ok BOOLEAN
TYPE Packet RECORD kind TEXT version INTEGER target Target request TEXT operation TEXT arguments ANY
TYPE Call RECORD p Packet e TEXT
TYPE Reply RECORD r ANY
TYPE Ack RECORD device ANY session ANY revision ANY sequence ANY
REG input Verified
REG source ANY
REG head ANY
REG state ANY
REG reply ANY
REG device ANY
REG session ANY
REG revision ANY
REG sequence ANY
REG args Ack
REG packet Packet
REG call Call
REG received Reply
REG kind TEXT
REG family TEXT
REG operation TEXT
REG request TEXT
REG one INTEGER
REG ok BOOLEAN
CONST one 1
XBUS.READ input x0
FIELD reply input r
FIELD ok input ok
JFALSE ok done
FIELD source input s
FIELD head source head
FIELD state source state
FIELD device state device
FIELD session state session
FIELD revision state revision
FIELD sequence head sequence
RECORD.MAKE args device device session session revision revision sequence sequence
CONST kind "stock_application_command"
CONST family "stock_application_reply"
CONST operation "ack"
CORRELATION request
RECORD.MAKE packet kind kind version one target output request request operation operation arguments args
RECORD.MAKE call p packet e family
XBUS.WRITE x1 call
XBUS.READ received x1
FIELD reply received r
done:
