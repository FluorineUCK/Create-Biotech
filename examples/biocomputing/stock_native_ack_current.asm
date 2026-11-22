; After the public delivery guard, refresh ONLY session before acknowledging.
; Preserve original device, catalog revision and input sequence: never ACK a replacement.
; x0 -> stock_native_guard.x1; x1 -> stock_native_ack_exchange.x0.
TYPE Verified RECORD s ANY j TEXT r ANY ok BOOLEAN
TYPE Command RECORD a ANY ok BOOLEAN
TYPE Reply RECORD r ANY
TYPE Ack RECORD device ANY session ANY revision ANY sequence ANY
REG input Verified
REG source ANY
REG head ANY
REG state ANY
REG fresh ANY
REG reply ANY
REG device ANY
REG session ANY
REG revision ANY
REG sequence ANY
REG args ANY
REG ackArgs Ack
REG call Command
REG received Reply
REG field TEXT
REG accepted TEXT
REG code ANY
REG ok BOOLEAN
REG acking BOOLEAN
XBUS.READ input x0
FIELD reply input r
FIELD ok input ok
JFALSE ok done
CONST acking false
CONST args {}
CONST field "state"
CONST accepted "ok"
send:
RECORD.MAKE call a args ok ok
XBUS.WRITE x1 call
XBUS.READ received x1
FIELD reply received r
JFALSE acking inspect
JMP done
inspect:
HAS.FIELD ok reply field
JFALSE ok decline
FIELD code reply code
VALUE.EQUALS ok code accepted
JFALSE ok decline
FIELD fresh reply state
FIELD session fresh session
FIELD source input s
FIELD head source head
FIELD state source state
FIELD device state device
FIELD revision state revision
FIELD sequence head sequence
RECORD.MAKE ackArgs device device session session revision revision sequence sequence
MOV args ackArgs
CONST acking true
JMP send
decline:
CONST ok false
RECORD.MAKE call a args ok ok
XBUS.WRITE x1 call
done:
