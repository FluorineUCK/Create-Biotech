; Bounded polling of the SAME business identity, never submit or retry a mutation.
; x0 shares handoff.x1; p0 reads handoff.p0=2. p1 selects the existing ACK guard.
; context.e is the ORIGINAL archived dispatch scope, never a newly sampled route.
; x1 shares packet_scoped.x0 with live.x0; ordinary packet software encodes every query.
TYPE Target RECORD x INTEGER y INTEGER z INTEGER
TYPE Context RECORD c ANY j TEXT i ANY e ANY s ANY
TYPE Finish RECORD c Context t Target
TYPE Data RECORD items ANY
TYPE Build RECORD t Target j TEXT i Data s ANY o TEXT e ANY
TYPE Reply RECORD r ANY

TYPE Completion RECORD s ANY j TEXT r ANY
REG input Finish
REG context Context
REG source ANY
REG target Target
REG scope ANY
REG data Data
REG state ANY
REG call Build
REG received Reply
REG completion Completion
REG reply ANY
REG phase ANY
REG job TEXT
REG operation TEXT
REG completed TEXT
REG field TEXT
REG attempts INTEGER
REG one INTEGER
REG selected INTEGER
REG zero INTEGER
REG selector INTEGER
REG ok BOOLEAN
CONST one 1
CONST selected 2
CONST zero 0
CONST data {"items":[]}
CONST state null
CONST operation "order"
CONST completed "complete"
CONST field "phase"
ready:
SLEEP one
SIO.READ selector p0
EQUAL_INTEGER ok selector selected
JFALSE ok ready
XBUS.READ input x0
FIELD context input c
FIELD target input t
FIELD source context s
FIELD job context j
FIELD scope context e
MOV reply context
MOV attempts poll_limit
RECORD.MAKE call t target j job i data s state o operation e scope
poll:
GREATER_INTEGER ok attempts zero
JFALSE ok done
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
