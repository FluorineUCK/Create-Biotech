; Bounded polling of the SAME business identity, never submit or retry a mutation.
; x0 shares supply.x0; p0 reads supply.p1. p1 selects the downstream ACK guard.
; x1 calls the existing serial_exchange x0 after supply has finished using it.
TYPE Target RECORD x INTEGER y INTEGER z INTEGER
TYPE Context RECORD c ANY j TEXT i ANY e ANY s ANY
TYPE Finish RECORD c Context t Target
TYPE Packet RECORD kind TEXT version INTEGER target Target request TEXT operation TEXT arguments ANY
TYPE Call RECORD p Packet e TEXT
TYPE Reply RECORD r ANY
TYPE Query RECORD order TEXT
TYPE Completion RECORD s ANY j TEXT r ANY
REG input Finish
REG context Context
REG source ANY
REG target Target
REG query Query
REG packet Packet
REG call Call
REG received Reply
REG completion Completion
REG reply ANY
REG phase ANY
REG job TEXT
REG kind TEXT
REG family TEXT
REG operation TEXT
REG request TEXT
REG completed TEXT
REG field TEXT
REG attempts INTEGER
REG one INTEGER
REG zero INTEGER
REG selector INTEGER
REG ok BOOLEAN
CONST one 1
CONST zero 0
CONST kind "production_command"
CONST family "production_reply"
CONST operation "order"
CONST completed "complete"
CONST field "phase"
ready:
SLEEP one
SIO.READ selector p0
EQUAL_INTEGER ok selector one
JFALSE ok ready
XBUS.READ input x0
FIELD context input c
FIELD target input t
FIELD source context s
FIELD job context j
MOV reply context
MOV attempts poll_limit
RECORD.MAKE query order job
poll:
GREATER_INTEGER ok attempts zero
JFALSE ok done
CORRELATION request
RECORD.MAKE packet kind kind version one target target request request operation operation arguments query
RECORD.MAKE call p packet e family
XBUS.WRITE x1 call
XBUS.READ received x1
FIELD reply received r
HAS.FIELD ok reply field
JFALSE ok done
FIELD phase reply phase
VALUE.EQUALS ok phase completed
JFALSE ok wait
JMP done
wait:
SUBTRACT_INTEGER attempts attempts one
SLEEP poll_ticks
JMP poll
done:
RECORD.MAKE completion s source j job r reply
SIO.WRITE p1 one
XBUS.WRITE x0 completion
SIO.WRITE p1 zero
