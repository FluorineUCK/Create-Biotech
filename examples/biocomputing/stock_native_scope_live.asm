; Live application: receive the public handoff on x1, archive, submit and return its exact scope.
; x0 connects packet_scoped.x0; x1 connects scope_archive.x0, p0 -> archive.p1.
; x1 is shared with archive.x0; archive reads only after this program drives p0=1.
TYPE Target RECORD x INTEGER y INTEGER z INTEGER
TYPE Head RECORD kind TEXT sequence INTEGER player TEXT address TEXT order NBT
TYPE Routed RECORD d TEXT h Head r ANY
TYPE Source RECORD d TEXT h Head
TYPE Data RECORD items ANY
TYPE Intake RECORD s Source i Data
TYPE Outcome RECORD r ANY j TEXT e ANY
TYPE Command RECORD t Target j TEXT i Data s ANY o TEXT e ANY
TYPE Received RECORD r ANY
TYPE Binding RECORD c TEXT j TEXT
REG intake Intake
REG source Source
REG outcome Outcome
REG routed Routed
REG head Head
REG data Data
REG command Command
REG received Received
REG binding Binding
REG reply ANY
REG state ANY
REG scope ANY
REG code ANY
REG device TEXT
REG job TEXT
REG operation TEXT
REG accepted TEXT
REG one INTEGER
REG zero INTEGER
REG size INTEGER
REG ok BOOLEAN
REG submitting BOOLEAN
CONST job ""
CONST accepted "ok"
CONST one 1
CONST zero 0
CONST state null
CONST scope null
CONST operation "status"
CONST submitting false
XBUS.READ intake x1
FIELD source intake s
FIELD data intake i
send:
RECORD.MAKE command t target j job i data s state o operation e scope
XBUS.WRITE x0 command
XBUS.READ received x0
FIELD reply received r
JFALSE submitting status
JMP done
status:
FIELD code reply code
VALUE.EQUALS ok code accepted
JFALSE ok done
FIELD state reply state
FIELD scope state scope
FIELD head source h
FIELD device source d
RECORD.MAKE routed d device h head r scope
SIO.WRITE p0 one
XBUS.WRITE x1 routed
XBUS.READ binding x1
SIO.WRITE p0 zero
MOV reply binding
FIELD job binding j
TEXT.LENGTH size job
GREATER_INTEGER ok size zero
JFALSE ok done
CONST operation "submit"
CONST submitting true
JMP send
done:
RECORD.MAKE outcome r reply j job e scope
SIO.WRITE p1 one
XBUS.WRITE x1 outcome
SIO.WRITE p1 zero
