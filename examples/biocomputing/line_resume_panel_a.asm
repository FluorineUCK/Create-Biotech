; Short workcell only: two isolated vanilla barrel receivers with a separate
; hard-wired interlock and a native one-shot timer. p0 is the trigger, not a latch.
TYPE Address RECORD x INTEGER y INTEGER z INTEGER
TYPE Binding RECORD reader Address block Address
TYPE Packet RECORD kind TEXT version INTEGER target Address request TEXT operation TEXT arguments ANY
TYPE Call RECORD p Packet e TEXT
TYPE Reply RECORD r ANY
TYPE Components LIST TEXT
TYPE Sample RECORD device ANY session ANY components Components
TYPE MaybeLevel OPTIONAL INTEGER
TYPE CheckedLevel RECORD ok BOOLEAN value MaybeLevel
TYPE Point RECORD index INTEGER offset Address type TEXT
TYPE Route RECORD input Point output Point
TYPE MaybeRoute OPTIONAL Route
TYPE CheckedRoute RECORD ok BOOLEAN value MaybeRoute
TYPE MaybeText OPTIONAL TEXT
TYPE CheckedText RECORD ok BOOLEAN value MaybeText
TYPE ArmArgs RECORD token TEXT input INTEGER output INTEGER
REG packet Packet
REG call Call
REG response Reply
REG sample Sample
REG binding Binding
REG target Address
REG other Address
REG kind TEXT
REG expect_kind TEXT
REG op TEXT
REG expect TEXT
REG request TEXT
REG field TEXT
REG version INTEGER
REG zero INTEGER
REG full INTEGER
REG pulse INTEGER
REG level INTEGER
REG match BOOLEAN
REG first BOOLEAN
REG checking_interlock BOOLEAN
REG checked CheckedLevel
REG maybe_level MaybeLevel
REG components Components
REG state ANY
REG block ANY
REG candidate ANY
REG args ANY
REG device ANY
REG session ANY
REG resume_status TEXT
REG resume_checkpoint TEXT
REG resume_reply ANY
REG planned Route
REG point Point
REG maybe_route MaybeRoute
REG checked_route CheckedRoute
REG maybe_text MaybeText
REG checked_text CheckedText
REG arm_args ArmArgs
REG token TEXT
REG from INTEGER
REG to INTEGER
REG observing_route BOOLEAN
REG route_wait INTEGER
REG route_reads INTEGER
REG one_read INTEGER
REG route_status TEXT
REG route_action ANY
REG route_reply ANY

CONST resume_checkpoint "none"
CONST resume_reply null
CONST route_status "not_requested"
CONST route_action null
CONST route_reply null
CONST route_reads 3
CONST one_read 1
CONST resume_status "release_not_armed"
JFALSE release_armed done
CONST resume_status "configuration_unconfirmed"
CONST expect "machines_checked_hold"
VALUE.EQUALS match machine_result expect
JFALSE match done

CONST resume_status "duplicate_receiver"
FIELD target outlet_one block
FIELD other outlet_two block
VALUE.EQUALS match target other
JFALSE match distinct_blocks
JMP done
distinct_blocks:
FIELD target outlet_one reader
FIELD other outlet_two reader
VALUE.EQUALS match target other
JFALSE match observe
JMP done

observe:
CONST version 1
CONST zero 0
CONST full 15
CONST kind "reader_command"
CONST expect_kind "reader_reply"
CONST components ["create_biotech:block","create_biotech:redstone"]
CONST first true
CONST checking_interlock false
MOV binding outlet_one
CONST resume_checkpoint "outlet_one"
start_probe:
FIELD target binding reader
CONST args {}
CONST op "status"

send:
CONST resume_status "outlet_unavailable"
CORRELATION request
RECORD.MAKE packet kind kind version version target target request request operation op arguments args
RECORD.MAKE call p packet e expect_kind
XBUS.WRITE x0 call
XBUS.READ response x0
FIELD resume_reply response r
FIELD candidate resume_reply code
CONST expect "ok"
VALUE.EQUALS match candidate expect
JFALSE match done
CONST expect "status"
VALUE.EQUALS match op expect
JFALSE match check_probe
FIELD state resume_reply state
FIELD device state device
FIELD session state session
RECORD.MAKE sample device device session session components components
MOV args sample
CONST op "sample"
JMP send

check_probe:
CONST resume_status "outlet_sample_incomplete"
FIELD state resume_reply value
CONST field "target"
HAS.FIELD match state field
JFALSE match done
FIELD candidate state target
FIELD target binding block
CONST resume_status "wrong_outlet_target"
VALUE.EQUALS match target candidate
JFALSE match done
CONST resume_status "outlet_sample_incomplete"
CONST field "components"
HAS.FIELD match state field
JFALSE match done
FIELD state state components
CONST field "create_biotech:block"
HAS.FIELD match state field
JFALSE match done
FIELD block state create_biotech:block
CONST field "id"
HAS.FIELD match block field
JFALSE match done
FIELD candidate block id
JFALSE checking_interlock barrel
JMP check_interlock
barrel:
CONST resume_status "wrong_outlet_block"
CONST expect "minecraft:barrel"
VALUE.EQUALS match candidate expect
JFALSE match done
CONST resume_status "outlet_sample_incomplete"
CONST field "create_biotech:redstone"
HAS.FIELD match state field
JFALSE match done
FIELD state state create_biotech:redstone
CONST field "comparator"
HAS.FIELD match state field
JFALSE match done
FIELD candidate state comparator
VALUE.CHECK checked candidate
FIELD match checked ok
JFALSE match done
FIELD maybe_level checked value
REQUIRE_VALUE level maybe_level
LESS_INTEGER match level zero
JFALSE match check_upper
JMP done
check_upper:
LESS_INTEGER match full level
JFALSE match check_empty
JMP done
check_empty:
CONST resume_status "outlet_blocked"
EQUAL_INTEGER match level zero
JFALSE match done
JFALSE first interlock
CONST first false
MOV binding outlet_two
CONST resume_checkpoint "outlet_two"
JMP start_probe

interlock:
; The external data cable exposes p0 only as an output. Inspect the separate
; native interlock wire through an authorized reader instead of a virtual p1.
CONST checking_interlock true
MOV binding interlock_probe
CONST resume_checkpoint "hardware_interlock"
CONST components ["create_biotech:block"]
JMP start_probe

check_interlock:
CONST resume_status "wrong_interlock_block"
CONST expect "minecraft:redstone_wire"
VALUE.EQUALS match candidate expect
JFALSE match done
CONST resume_status "interlock_sample_incomplete"
CONST field "state"
HAS.FIELD match block field
JFALSE match done
FIELD state block state
CONST field "power"
HAS.FIELD match state field
JFALSE match done
FIELD candidate state power
CONST resume_status "interlock_not_ready"
CONST expect "15"
VALUE.EQUALS match candidate expect
JFALSE match done
JFALSE route_enabled trigger

; Use the very snapshot that verified these points. Re-preparing by index could
; bind a different placement if a player edits the arm during the serial waits.
CONST resume_status "route_unconfirmed"
CONST resume_checkpoint "arm_admission"
CONST route_status "route_snapshot_invalid"
VALUE.CHECK checked_route confirmed_route
FIELD match checked_route ok
JFALSE match done
FIELD maybe_route checked_route value
REQUIRE_VALUE planned maybe_route
FIELD target layout arm
CONST field "target"
HAS.FIELD match machine_reply field
JFALSE match done
FIELD candidate machine_reply target
VALUE.EQUALS match candidate target
JFALSE match done
CONST field "state"
HAS.FIELD match machine_reply field
JFALSE match done
FIELD state machine_reply state
CONST field "token"
HAS.FIELD match state field
JFALSE match done
FIELD candidate state token
VALUE.CHECK checked_text candidate
FIELD match checked_text ok
JFALSE match done
FIELD maybe_text checked_text value
REQUIRE_VALUE token maybe_text
CONST expect ""
VALUE.EQUALS match token expect
JFALSE match submit_route
JMP done

submit_route:
FIELD point planned input
FIELD from point index
FIELD point planned output
FIELD to point index
RECORD.MAKE arm_args token token input from output to
MOV args arm_args
CONST observing_route false
CONST op "arm_move"
CONST expect "accepted"
CONST route_status "admission_unknown"
JMP send_route

observe_route:
; Bounded passive observations of the same action, not a travel timer.
CONST route_wait 160
SLEEP route_wait
CONST observing_route true
CONST args {}
CONST op "arm_status"
CONST expect "ok"
CONST route_status "observation_unknown"

send_route:
CONST kind "component_settings_command"
CONST expect_kind "component_settings_reply"
CORRELATION request
RECORD.MAKE packet kind kind version version target target request request operation op arguments args
RECORD.MAKE call p packet e expect_kind
XBUS.WRITE x0 call
XBUS.READ response x0
FIELD route_reply response r
CONST field "code"
HAS.FIELD match route_reply field
JFALSE match done
FIELD candidate route_reply code
VALUE.EQUALS match candidate expect
JFALSE match route_rejected
CONST field "state"
HAS.FIELD match route_reply field
JFALSE match done
FIELD state route_reply state
CONST field "action"
HAS.FIELD match state field
JFALSE match done
FIELD candidate state action
JFALSE observing_route admitted
CONST route_status "action_changed"
VALUE.EQUALS match candidate route_action
JFALSE match done
CONST route_status "observation_unknown"
CONST field "transfer"
HAS.FIELD match state field
JFALSE match done
FIELD candidate state transfer
CONST expect "moving"
VALUE.EQUALS match candidate expect
JFALSE match check_routing
JMP continue_observing
check_routing:
CONST expect "routing"
VALUE.EQUALS match candidate expect
JFALSE match route_code
continue_observing:
SUBTRACT_INTEGER route_reads route_reads one_read
LESS_INTEGER match zero route_reads
JFALSE match route_code
JMP observe_route

admitted:
VALUE.CHECK checked_text candidate
FIELD match checked_text ok
JFALSE match done
FIELD maybe_text checked_text value
REQUIRE_VALUE token maybe_text
CONST expect ""
VALUE.EQUALS match token expect
JFALSE match remember_action
JMP done
remember_action:
MOV route_action token
CONST route_status "accepted"
JMP trigger

route_rejected:
; A timeout is not proof that the device refused the move. Nothing
; is resubmitted; the operator can inspect the arm before resetting.
CONST expect "receive_timeout"
VALUE.EQUALS match candidate expect
JFALSE match route_code
JMP done
route_code:
VALUE.CHECK checked_text candidate
FIELD match checked_text ok
JFALSE match done
FIELD maybe_text checked_text value
REQUIRE_VALUE route_status maybe_text
JMP done

trigger:
; The two-tick trigger is separate from the actual hardware's open duration.
CONST pulse 2
CONST resume_status "request_in_progress"
SIO.WRITE p0 full
SLEEP pulse
SIO.WRITE p0 zero
CONST resume_checkpoint "finished"
CONST resume_status "release_pulsed"
JFALSE route_enabled done
JMP observe_route
done:
