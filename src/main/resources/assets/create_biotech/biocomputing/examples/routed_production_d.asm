; PUBLIC ROUTED MATERIAL SUBMISSION, not manufacturing completion or device authorization.
; Explicit wire A.x0<->D.x0, A.x1<->E.x0, D.x1<->production_b.x0, B.x1<->production_c.x0.
; Start E/D/C/B before A. Persistent services must be paused explicitly afterward; admitted faults never retry.
; A inputs target,stable job,expected_address,exact items,poll_ticks. Existing production_poll_a queries directly through B/C.
; A result c=local code,e=expected,a=observed current address,o=frozen order address,r=unaltered raw reply.
; checked_reply ONLY means frozen address matches: inspect r.code/phase/diagnostic/milestone, never infer manufacturing completion.
; D job t=target,j=stable job,i=unchanged items,s=raw status state,o=status|submit; D constructs ALL data.items/envelopes/correlation.
; E request r=raw reply,e=expected address,s=false current status/true historical order; reply c=local code,a=route fact.
; E checks editable data copies, not permissions. The physical production host alone authorizes and freezes the actual route.
; Explicit envelope service only. Receive on x0, public B on x1; every complete frame built by ISA.
TYPE Target RECORD x INTEGER y INTEGER z INTEGER
TYPE Packet RECORD kind TEXT version INTEGER target Target request TEXT operation TEXT arguments ANY
TYPE Outcome RECORD ok BOOLEAN value ANY
TYPE Items LIST ANY
TYPE Data RECORD items Items
TYPE Submission RECORD device ANY session ANY order TEXT data Data
TYPE Job RECORD t Target j TEXT i Items s ANY o TEXT
REG job Job
REG target Target
REG stable_job TEXT
REG items Items
REG state ANY
REG operation TEXT
REG status INTEGER
REG fifteen INTEGER
REG flag BOOLEAN
REG expected TEXT
REG kind TEXT
REG version INTEGER
REG request TEXT
REG arguments ANY
REG data Data
REG submission Submission
REG device ANY
REG session ANY
REG packet Packet
REG outcome Outcome
CONST kind "production_command"
CONST version 1
CONST fifteen 15
CONST expected "status"
receive:
XBUS.STATUS status x0
EQUAL_INTEGER flag status fifteen
JFALSE flag pause_receive
XBUS.READ job x0
FIELD target job t
FIELD stable_job job j
FIELD items job i
FIELD state job s
FIELD operation job o
CONST arguments {}
VALUE.EQUALS flag operation expected
JFALSE flag submit
JMP send
submit:
FIELD device state device
FIELD session state session
RECORD.MAKE data items items
RECORD.MAKE submission device device session session order stable_job data data
MOV arguments submission
send:
CORRELATION request
RECORD.MAKE packet kind kind version version target target request request operation operation arguments arguments
XBUS.WRITE x1 packet
response:
XBUS.STATUS status x1
EQUAL_INTEGER flag status fifteen
JFALSE flag pause_response
XBUS.READ outcome x1
XBUS.WRITE x0 outcome
JMP receive
pause_receive:
SLEEP poll_ticks
JMP receive
pause_response:
SLEEP poll_ticks
JMP response
