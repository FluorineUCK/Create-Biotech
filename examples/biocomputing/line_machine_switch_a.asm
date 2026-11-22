; Called only after the public filters have finished their write and readback.
; Check native placement descriptors; no settings other than mode, no movement and no feed output.
TYPE Address RECORD x INTEGER y INTEGER z INTEGER
TYPE Point RECORD index INTEGER offset Address type TEXT
TYPE Packet RECORD kind TEXT version INTEGER target Address request TEXT operation TEXT arguments ANY
TYPE Call RECORD p Packet e TEXT
TYPE Reply RECORD r ANY
TYPE StatusArgs RECORD field TEXT
TYPE ModeArgs RECORD field TEXT token ANY value TEXT
TYPE Points LIST ANY
TYPE MaybePoints OPTIONAL Points
TYPE CheckedPoints RECORD ok BOOLEAN value MaybePoints
REG packet Packet
REG call Call
REG response Reply
REG query StatusArgs
REG change ModeArgs
REG target Address
REG kind TEXT
REG expect_kind TEXT
REG operation TEXT
REG request TEXT
REG mode_field TEXT
REG key TEXT
REG expected TEXT
REG version INTEGER
REG zero INTEGER
REG index INTEGER
REG size INTEGER
REG match BOOLEAN
REG output_point BOOLEAN
REG planned Point
REG planned_offset Address
REG other_offset Address
REG checked CheckedPoints
REG maybe_points MaybePoints
REG points Points
REG args ANY
REG code ANY
REG state ANY
REG actual ANY
REG field_value ANY
REG token ANY
REG machine_result TEXT
REG machine_checkpoint TEXT
REG deployer_write ANY
REG deployer_verify ANY
REG arm_verify ANY
REG machine_reply ANY
REG confirmed_route ANY

CONST machine_result "filters_blocked"
CONST machine_checkpoint "none"
CONST deployer_write "not_attempted"
CONST deployer_verify "not_attempted"
CONST arm_verify "not_attempted"
CONST machine_reply null
CONST confirmed_route null
CONST expected "filtered_hold"
VALUE.EQUALS match filter_result expected
JFALSE match done

CONST machine_result "invalid_machine_profile"
CONST expected "use"
VALUE.EQUALS match deployer_mode expected
JFALSE match try_punch
JMP check_profile
try_punch:
CONST expected "punch"
VALUE.EQUALS match deployer_mode expected
JFALSE match done
check_profile:
CONST zero 0
FIELD planned arm_route input
FIELD index planned index
LESS_INTEGER match index zero
JFALSE match input_nonnegative
JMP done
input_nonnegative:
FIELD planned_offset planned offset
FIELD planned arm_route output
FIELD index planned index
LESS_INTEGER match index zero
JFALSE match output_nonnegative
JMP done
output_nonnegative:
FIELD other_offset planned offset
VALUE.EQUALS match planned_offset other_offset
JFALSE match query_mode
JMP done

query_mode:
CONST kind "component_settings_command"
CONST expect_kind "component_settings_reply"
CONST version 1
CONST mode_field "mode"
CONST machine_checkpoint "deployer_query"
FIELD target layout deployer
CONST operation "status"
RECORD.MAKE query field mode_field
MOV args query
send:
CORRELATION request
RECORD.MAKE packet kind kind version version target target request request operation operation arguments args
RECORD.MAKE call p packet e expect_kind
XBUS.WRITE x0 call
XBUS.READ response x0
FIELD machine_reply response r
CONST machine_result "invalid_machine_reply"
CONST key "code"
HAS.FIELD match machine_reply key
JFALSE match done
FIELD code machine_reply code

CONST expected "set"
VALUE.EQUALS match operation expected
JFALSE match query_result
MOV deployer_write code
CONST machine_result "deployer_write_not_confirmed"
CONST expected "applied"
VALUE.EQUALS match code expected
JFALSE match done
JMP verify_deployer

query_result:
CONST expected "ok"
VALUE.EQUALS match code expected
JFALSE match query_error
CONST key "state"
HAS.FIELD match machine_reply key
JFALSE match done
FIELD state machine_reply state
CONST expected "status"
VALUE.EQUALS match operation expected
JFALSE match followup
CONST machine_result "invalid_mode_status"
CONST key "device"
HAS.FIELD match state key
JFALSE match done
FIELD actual state device
CONST expected "create:deployer"
VALUE.EQUALS match actual expected
JFALSE match done
CONST key "field"
HAS.FIELD match state key
JFALSE match done
FIELD actual state field
VALUE.EQUALS match actual mode_field
JFALSE match done
CONST key "value"
HAS.FIELD match state key
JFALSE match done
FIELD actual state value
VALUE.EQUALS match actual deployer_mode
JFALSE match write_mode
CONST deployer_write "unchanged"
JMP verify_deployer
write_mode:
CONST key "token"
HAS.FIELD match state key
JFALSE match done
FIELD token state token
RECORD.MAKE change field mode_field token token value deployer_mode
MOV args change
CONST machine_checkpoint "deployer_set"
CONST operation "set"
JMP send

query_error:
CONST machine_result "machine_query_failed"
CONST expected "deployer_status"
VALUE.EQUALS match operation expected
JFALSE match try_arm_error
MOV deployer_verify code
JMP done
try_arm_error:
CONST expected "arm_status"
VALUE.EQUALS match operation expected
JFALSE match done
MOV arm_verify code
JMP done

verify_deployer:
CONST machine_checkpoint "deployer_verify"
CONST operation "deployer_status"
CONST args {}
JMP send
followup:
CONST expected "deployer_status"
VALUE.EQUALS match operation expected
JFALSE match verify_arm
CONST machine_result "deployer_verification_failed"
CONST deployer_verify "mode_mismatch"
CONST key "mode"
HAS.FIELD match state key
JFALSE match done
FIELD actual state mode
VALUE.EQUALS match actual deployer_mode
JFALSE match done
CONST deployer_verify "deployer_not_idle"
CONST key "locked"
HAS.FIELD match state key
JFALSE match done
FIELD actual state locked
CONST field_value true
VALUE.EQUALS match actual field_value
JFALSE match done
CONST key "phase"
HAS.FIELD match state key
JFALSE match done
FIELD actual state phase
CONST expected "waiting"
VALUE.EQUALS match actual expected
JFALSE match done
CONST key "process"
HAS.FIELD match state key
JFALSE match done
FIELD actual state process
CONST expected "idle"
VALUE.EQUALS match actual expected
JFALSE match deployer_processed
JMP query_arm
deployer_processed:
CONST expected "processed"
VALUE.EQUALS match actual expected
JFALSE match done

query_arm:
CONST deployer_verify "verified"
FIELD target layout arm
CONST machine_checkpoint "arm_verify"
CONST operation "arm_status"
CONST args {}
JMP send

verify_arm:
CONST machine_result "arm_verification_failed"
CONST arm_verify "arm_not_idle"
CONST key "held"
HAS.FIELD match state key
JFALSE match done
FIELD actual state held
IS_NULL match actual
JFALSE match done
CONST key "phase"
HAS.FIELD match state key
JFALSE match done
FIELD actual state phase
CONST expected "search_inputs"
VALUE.EQUALS match actual expected
JFALSE match done
CONST key "transfer"
HAS.FIELD match state key
JFALSE match done
FIELD actual state transfer
CONST expected "idle"
VALUE.EQUALS match actual expected
JFALSE match arm_previous
JMP input_point
arm_previous:
CONST expected "transfer_completed"
VALUE.EQUALS match actual expected
JFALSE match done

input_point:
CONST arm_verify "route_unconfirmed"
CONST output_point false
CONST key "inputs"
HAS.FIELD match state key
JFALSE match done
FIELD field_value state inputs
FIELD planned arm_route input
JMP check_point
choose_output:
CONST output_point true
CONST key "outputs"
HAS.FIELD match state key
JFALSE match done
FIELD field_value state outputs
FIELD planned arm_route output
check_point:
VALUE.CHECK checked field_value
FIELD match checked ok
JFALSE match done
FIELD maybe_points checked value
REQUIRE_VALUE points maybe_points
LIST.LENGTH size points
FIELD index planned index
LESS_INTEGER match index size
JFALSE match done
LIST.GET actual points index
CONST key "status"
HAS.FIELD match actual key
JFALSE match done
FIELD code actual status
CONST expected "ready"
VALUE.EQUALS match code expected
JFALSE match unavailable_point
CONST arm_verify "route_mismatch"
CONST key "index"
HAS.FIELD match actual key
JFALSE match done
FIELD field_value actual index
VALUE.EQUALS match field_value index
JFALSE match done
CONST key "offset"
HAS.FIELD match actual key
JFALSE match done
FIELD field_value actual offset
FIELD planned_offset planned offset
VALUE.EQUALS match field_value planned_offset
JFALSE match done
CONST key "type"
HAS.FIELD match actual key
JFALSE match done
FIELD field_value actual type
FIELD expected planned type
VALUE.EQUALS match field_value expected
JFALSE match done
CONST arm_verify "route_unconfirmed"
JFALSE output_point choose_output
CONST arm_verify "verified"
MOV confirmed_route arm_route
CONST machine_result "machines_checked_hold"
CONST machine_checkpoint "finished"
JMP done
unavailable_point:
MOV arm_verify code
done:
