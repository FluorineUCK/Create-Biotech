; PUBLIC ROUTED E@2, Assembly v1. Existing A/E/D/B/C interfaces remain unchanged.
; Explicit SIX Computers: A.x0<->D.x0, A.x1<->E.x0, E.x1<->F.x0,
; D.x1<->B.x0, B.x1<->C.x0. Install F@1 and start F BEFORE E; then D/C/B, finally A.
; F inspects a supplied State copy only; it grants no permission or inventory/route authority.
; E writes the checked State and then blocks its own task for the fresh {ok:BOOLEAN} reply.
; No buffered messaging, hidden retry/reset/timeout or automatic wiring/install.
; Persistent E/F/D/B/C must be paused explicitly after use. Admitted faults stay ordinary faults.
; Request r=raw reply,e=expected,s=false current/true historical; reply c=local code,a=route fact.
; Original raw device reply remains in A. checked_reply is NOT manufacturing completion.
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
REG state ANY
REG checked_state CheckedState
REG maybe_state MaybeState
REG confirmed State
REG address TEXT
TYPE Policy RECORD ok BOOLEAN
REG policy Policy
REG checked_text CheckedText
CONST expected "ok"
CONST fifteen 15
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
XBUS.WRITE x1 confirmed
XBUS.READ policy x1
FIELD flag policy ok
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
