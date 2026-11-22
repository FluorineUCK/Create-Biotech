; Receives the EXISTING preparer's Work; never allocates a job or changes the native input.
; x0 shares prepare.x0/sampler.x1; p1 reads prepare.p0=1.
; x1 shares live.x1/archive.x0/finish.x0; p0=2 selects finish only AFTER live returns.
TYPE Work RECORD s ANY v ANY
TYPE Identity RECORD d ANY h ANY
TYPE Data RECORD items ANY
TYPE Intake RECORD s Identity i Data
TYPE Outcome RECORD r ANY j TEXT e ANY
TYPE Context RECORD c ANY j TEXT i ANY e ANY s ANY
TYPE Target RECORD x INTEGER y INTEGER z INTEGER
TYPE Finish RECORD c Context t Target
REG work Work
REG identity Identity
REG data Data
REG intake Intake
REG outcome Outcome
REG context Context
REG finish Finish
REG source ANY
REG state ANY
REG head ANY
REG device ANY
REG converted ANY
REG items ANY
REG code ANY
REG scope ANY
REG reply ANY
REG job TEXT
REG accepted TEXT
REG field TEXT
REG one INTEGER
REG two INTEGER
REG zero INTEGER
REG selector INTEGER
REG ok BOOLEAN
CONST one 1
CONST two 2
CONST zero 0
CONST accepted "ok"
CONST field "phase"
ready:
SLEEP one
SIO.READ selector p1
EQUAL_INTEGER ok selector one
JFALSE ok ready
XBUS.READ work x0
FIELD converted work v
MOV reply converted
FIELD code converted c
VALUE.EQUALS ok code accepted
JFALSE ok done
FIELD source work s
FIELD state source state
FIELD device state device
FIELD head source head
FIELD items converted i
RECORD.MAKE identity d device h head
RECORD.MAKE data items items
RECORD.MAKE intake s identity i data
XBUS.WRITE x1 intake
; Do not read while live uses this shared bus to talk to the archive.
; live.p1 -> this p0; this p0 -> finish.p0. 1 means outcome, 2 means finish input.
outcome_ready:
SLEEP one
SIO.READ selector p0
EQUAL_INTEGER ok selector one
JFALSE ok outcome_ready
XBUS.READ outcome x1
FIELD reply outcome r
HAS.FIELD ok reply field
JFALSE ok done
FIELD job outcome j
FIELD scope outcome e
RECORD.MAKE context c accepted j job i items e scope s source
RECORD.MAKE finish c context t target
SIO.WRITE p0 two
XBUS.WRITE x1 finish
SIO.WRITE p0 zero
done:
