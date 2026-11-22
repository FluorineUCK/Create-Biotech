; Stops and inspects via the caller's line_drain_a before entering this procedure.
; Fresh filter credentials for each slot; no retries, rollback, or feed output.
TYPE Address RECORD x INTEGER y INTEGER z INTEGER
TYPE Rule RECORD domain TEXT mode TEXT matchers ANY
TYPE Filter RECORD target Address rule Rule
TYPE Packet RECORD kind TEXT version INTEGER target Address request TEXT operation TEXT arguments ANY
TYPE Call RECORD p Packet e TEXT
TYPE Reply RECORD r ANY
TYPE RuleArgs RECORD state ANY rule Rule
TYPE Steps LIST TEXT
TYPE MaybeText OPTIONAL TEXT
TYPE CheckedText RECORD ok BOOLEAN value MaybeText
REG packet Packet
REG call Call
REG answer Reply
REG action RuleArgs
REG filter Filter
REG rule Rule
REG target Address
REG other Address
REG devices Steps
REG operations Steps
REG checked CheckedText
REG maybe_text MaybeText
REG kind TEXT
REG reply_kind TEXT
REG request TEXT
REG expected TEXT
REG column TEXT
REG code TEXT
REG side TEXT
REG index INTEGER
REG one INTEGER
REG count INTEGER
REG version INTEGER
REG match BOOLEAN
REG is_item BOOLEAN
REG args ANY
REG raw_code ANY
REG token ANY
REG item_token ANY
REG fluid_token ANY
REG filter_result TEXT
REG filter_checkpoint TEXT
REG filter_operation TEXT
REG item_write ANY
REG fluid_write ANY
REG item_verify ANY
REG fluid_verify ANY
REG filter_reply ANY

CONST filter_result "drain_blocked"
CONST filter_checkpoint "none"
CONST filter_operation "none"
CONST item_write "not_attempted"
CONST fluid_write "not_attempted"
CONST item_verify "not_attempted"
CONST fluid_verify "not_attempted"
CONST filter_reply null
JFALSE guard_ok done

CONST filter_result "invalid_profile"
FIELD target item_filter target
FIELD other fluid_filter target
VALUE.EQUALS match target other
JFALSE match item_domain
JMP done
item_domain:
FIELD rule item_filter rule
FIELD side rule domain
CONST expected "item"
VALUE.EQUALS match side expected
JFALSE match done
FIELD rule fluid_filter rule
FIELD side rule domain
CONST expected "fluid"
VALUE.EQUALS match side expected
JFALSE match done

CONST devices ["item_filter","item_filter","fluid_filter","fluid_filter","item_filter","fluid_filter"]
CONST operations ["status","set","status","set","verify","verify"]
LIST.LENGTH count operations
CONST one 1
CONST version 1
CONST index 0
CONST kind "virtual_filter_command"
CONST reply_kind "virtual_filter_reply"
CONST item_token null
CONST fluid_token null
next:
LIST.GET filter_checkpoint devices index
LIST.GET filter_operation operations index
CONST expected "item_filter"
VALUE.EQUALS is_item filter_checkpoint expected
JFALSE is_item fluid_step
MOV filter item_filter
MOV token item_token
JMP prepare
fluid_step:
MOV filter fluid_filter
MOV token fluid_token
prepare:
FIELD target filter target
FIELD rule filter rule
CONST args {}
CONST expected "status"
VALUE.EQUALS match filter_operation expected
JFALSE match rule_args
JMP send
rule_args:
RECORD.MAKE action state token rule rule
MOV args action
send:
CORRELATION request
RECORD.MAKE packet kind kind version version target target request request operation filter_operation arguments args
RECORD.MAKE call p packet e reply_kind
XBUS.WRITE x0 call
XBUS.READ answer x0
FIELD filter_reply answer r
CONST filter_result "invalid_filter_reply"
CONST column "code"
HAS.FIELD match filter_reply column
JFALSE match done
FIELD raw_code filter_reply code
VALUE.CHECK checked raw_code
FIELD match checked ok
JFALSE match done
FIELD maybe_text checked value
REQUIRE_VALUE code maybe_text

CONST expected "status"
VALUE.EQUALS match filter_operation expected
JFALSE match check_is_set
CONST filter_result "status_failed"
CONST expected "ok"
VALUE.EQUALS match code expected
JFALSE match done
JMP remember_token

check_is_set:
CONST expected "set"
VALUE.EQUALS match filter_operation expected
JFALSE match verify
JFALSE is_item fluid_write_result
MOV item_write code
JMP check_write_result
fluid_write_result:
MOV fluid_write code
check_write_result:
CONST filter_result "write_not_confirmed"
CONST expected "applied"
VALUE.EQUALS match code expected
JFALSE match done

remember_token:
CONST filter_result "invalid_filter_reply"
CONST column "state"
HAS.FIELD match filter_reply column
JFALSE match done
FIELD token filter_reply state
JFALSE is_item store_fluid
MOV item_token token
JMP advance
store_fluid:
MOV fluid_token token
JMP advance

verify:
JFALSE is_item fluid_verify_result
MOV item_verify code
JMP check_verify_result
fluid_verify_result:
MOV fluid_verify code
check_verify_result:
CONST filter_result "verification_failed"
CONST expected "verified"
VALUE.EQUALS match code expected
JFALSE match done

advance:
ADD_INTEGER index index one
LESS_INTEGER match index count
JFALSE match finished
JMP next
finished:
CONST filter_checkpoint "finished"
CONST filter_operation "none"
CONST filter_result "filtered_hold"
done:
