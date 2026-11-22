; A.x0 to the existing public serial_exchange x0. One particle burst per explicit run.
; emit=false only queries. emit=true uses the current token for one bounded burst.
TYPE Target RECORD x INTEGER y INTEGER z INTEGER
TYPE Packet RECORD kind TEXT version INTEGER target Target request TEXT operation TEXT arguments ANY
TYPE Call RECORD p Packet e TEXT
TYPE Reply RECORD r ANY
TYPE Burst RECORD kind TEXT count INTEGER offset ANY spread ANY speed ANY options ANY
TYPE BurstArgs RECORD device ANY session ANY revision ANY burst Burst
REG packet Packet
REG call Call
REG received Reply
REG action BurstArgs
REG args ANY
REG state ANY
REG device ANY
REG session ANY
REG revision ANY
REG kind TEXT
REG expected TEXT
REG op TEXT
REG request TEXT
REG field TEXT
REG version INTEGER
REG ok BOOLEAN
REG reply ANY
CONST kind "particle_output_command"
CONST expected "particle_output_reply"
CONST version 1
CONST op "status"
CONST args {}
CONST field "state"
send:
CORRELATION request
RECORD.MAKE packet kind kind version version target target request request operation op arguments args
RECORD.MAKE call p packet e expected
XBUS.WRITE x0 call
XBUS.READ received x0
FIELD reply received r
JFALSE emit done
HAS.FIELD ok reply field
JFALSE ok done
FIELD state reply state
FIELD device state device
FIELD session state session
FIELD revision state revision
RECORD.MAKE action device device session session revision revision burst burst
MOV args action
; Clear the invocation-local emit flag before the single send.
CONST emit false
CONST op "emit"
JMP send
done:
