; PUBLIC ROUTED MATERIAL SUBMISSION, not manufacturing completion or device authorization.
; Explicit wire A.x0<->D.x0, A.x1<->E.x0, D.x1<->production_b.x0, B.x1<->production_c.x0.
; Start E/D/C/B before A. Persistent services must be paused explicitly afterward; admitted faults never retry.
; A inputs target,stable job,expected_address,exact items,poll_ticks. Existing production_poll_a queries directly through B/C.
; A result c=local code,e=expected,a=observed current address,o=frozen order address,r=unaltered raw reply.
; checked_reply ONLY means frozen address matches: inspect r.code/phase/diagnostic/milestone, never infer manufacturing completion.
; D job t=target,j=stable job,i=unchanged items,s=raw status state,o=status|submit; D constructs ALL data.items/envelopes/correlation.
; E request r=raw reply,e=expected address,s=false current status/true historical order; reply c=local code,a=route fact.
; E checks editable data copies, not permissions. The physical production host alone authorizes and freezes the actual route.
; Pure data route checks in ordinary ISA, not device authorization or a free remote call.
TYPE State RECORD address TEXT device TEXT session TEXT revision INTEGER
TYPE MaybeState OPTIONAL State
TYPE CheckedState RECORD ok BOOLEAN value MaybeState
TYPE MaybeText OPTIONAL TEXT
TYPE CheckedText RECORD ok BOOLEAN value MaybeText
TYPE Inspection RECORD r ANY e TEXT s BOOLEAN
TYPE Decision RECORD c TEXT a ANY
REG incoming Inspection
REG reply ANY
REG expected_address TEXT
REG submitting BOOLEAN
REG result Decision
REG fact ANY
REG local_code TEXT
REG key TEXT
REG code ANY
REG expected TEXT
REG flag BOOLEAN
REG status INTEGER
REG fifteen INTEGER
REG zero INTEGER
REG bound INTEGER
REG state ANY
REG checked_state CheckedState
REG maybe_state MaybeState
REG confirmed State
REG address TEXT
REG length INTEGER
REG checked_text CheckedText
CONST expected "ok"
CONST fifteen 15
CONST zero 0
CONST bound 26
receive:
XBUS.STATUS status x0
EQUAL_INTEGER flag status fifteen
JFALSE flag pause_receive
XBUS.READ incoming x0
FIELD reply incoming r
FIELD expected_address incoming e
FIELD submitting incoming s
CONST fact null
CONST local_code "device_reply"
JFALSE submitting current_route
CONST key "phase"
HAS.FIELD flag reply key
JFALSE flag finish
CONST local_code "historical_route_unavailable"
CONST key "address"
HAS.FIELD flag reply key
JFALSE flag finish
FIELD fact reply address
VALUE.CHECK checked_text fact
FIELD flag checked_text ok
JFALSE flag finish
VALUE.EQUALS flag fact expected_address
CONST local_code "historical_route_mismatch"
JFALSE flag finish
CONST local_code "checked_reply"
JMP finish
current_route:
FIELD code reply code
VALUE.EQUALS flag code expected
JFALSE flag finish
CONST local_code "route_unavailable"
CONST key "state"
HAS.FIELD flag reply key
JFALSE flag finish
FIELD state reply state
VALUE.CHECK checked_state state
FIELD flag checked_state ok
JFALSE flag finish
FIELD maybe_state checked_state value
REQUIRE_VALUE confirmed maybe_state
FIELD address confirmed address
MOV fact address
TEXT.LENGTH length address
GREATER_INTEGER flag length zero
JFALSE flag finish
LESS_INTEGER flag length bound
JFALSE flag finish
VALUE.EQUALS flag address expected_address
CONST local_code "route_mismatch"
JFALSE flag finish
CONST local_code "route_matched"
finish:
RECORD.MAKE result c local_code a fact
XBUS.WRITE x0 result
JMP receive
pause_receive:
SLEEP poll_ticks
JMP receive
