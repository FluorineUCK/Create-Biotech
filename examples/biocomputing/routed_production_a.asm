; PUBLIC ROUTED MATERIAL SUBMISSION, not manufacturing completion or device authorization.
; Explicit wire A.x0<->D.x0, A.x1<->E.x0, D.x1<->production_b.x0, B.x1<->production_c.x0.
; Start E/D/C/B before A. Persistent services must be paused explicitly afterward; admitted faults never retry.
; A inputs target,stable job,expected_address,exact items,poll_ticks. Existing production_poll_a queries directly through B/C.
; A result c=local code,e=expected,a=observed current address,o=frozen order address,r=unaltered raw reply.
; checked_reply ONLY means frozen address matches: inspect r.code/phase/diagnostic/milestone, never infer manufacturing completion.
; D job t=target,j=stable job,i=unchanged items,s=raw status state,o=status|submit; D constructs ALL data.items/envelopes/correlation.
; E request r=raw reply,e=expected address,s=false current status/true historical order; reply c=local code,a=route fact.
; E checks editable data copies, not permissions. The physical production host alone authorizes and freezes the actual route.
; Result short fields: c=local code,e=expected address,a=current address,o=frozen order address,r=actual raw reply.
; Complete routed production probe: no new material/order/retry logic in Java.
TYPE Target RECORD x INTEGER y INTEGER z INTEGER
TYPE Packet RECORD kind TEXT version INTEGER target Target request TEXT operation TEXT arguments ANY
TYPE Outcome RECORD ok BOOLEAN value ANY
TYPE Items LIST ANY
TYPE Job RECORD t Target j TEXT i Items s ANY o TEXT
TYPE Data RECORD items Items
TYPE Submission RECORD device TEXT session TEXT order TEXT data Data
TYPE State RECORD address TEXT device TEXT session TEXT revision INTEGER
TYPE MaybeState OPTIONAL State
TYPE CheckedState RECORD ok BOOLEAN value MaybeState
TYPE MaybeText OPTIONAL TEXT
TYPE CheckedText RECORD ok BOOLEAN value MaybeText
TYPE Inspection RECORD r ANY e TEXT s BOOLEAN
TYPE Decision RECORD c TEXT a ANY
REG inspection Inspection
REG decision Decision
REG fact ANY
REG accepted TEXT
REG result OUTPUT result
REG message Job
REG outcome Outcome
REG operation TEXT
REG reply ANY
REG state ANY
REG current ANY
REG frozen ANY
REG local_code TEXT
REG flag BOOLEAN
REG submitting BOOLEAN
REG status INTEGER
REG fifteen INTEGER
CONST operation "status"
CONST state null
CONST accepted "route_matched"
CONST submitting false
CONST current null
CONST frozen null
CONST local_code "transport_error"
CONST fifteen 15
send:
RECORD.MAKE message t target j job i items s state o operation
XBUS.WRITE x0 message
response:
XBUS.STATUS status x0
EQUAL_INTEGER flag status fifteen
JFALSE flag pause_response
XBUS.READ outcome x0
FIELD flag outcome ok
FIELD reply outcome value
JFALSE flag done
CONST local_code "device_reply"
inspect:
RECORD.MAKE inspection r reply e expected_address s submitting
XBUS.WRITE x1 inspection
inspection_response:
XBUS.STATUS status x1
EQUAL_INTEGER flag status fifteen
JFALSE flag pause_inspection
XBUS.READ decision x1
FIELD local_code decision c
FIELD fact decision a
JFALSE submitting checked_status
MOV frozen fact
JMP done
checked_status:
MOV current fact
VALUE.EQUALS flag local_code accepted
JFALSE flag done
FIELD state reply state
CONST operation "submit"
CONST submitting true
CONST local_code "transport_error"
JMP send
done:
RECORD.MAKE result c local_code e expected_address a current o frozen r reply
RET
pause_response:
SLEEP poll_ticks
JMP response
pause_inspection:
SLEEP poll_ticks
JMP inspection_response
