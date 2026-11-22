; First part of the two-process workcell: close feed, then observe the configured
; gated workcell. No setting/start frame and no reopen on either path.
; x0 calls the public serial_exchange relay; p0 is active-high feed permission.
TYPE Address RECORD x INTEGER y INTEGER z INTEGER
TYPE Binding RECORD reader Address block Address
TYPE Packet RECORD kind TEXT version INTEGER target Address request TEXT operation TEXT arguments ANY
TYPE Call RECORD p Packet e TEXT
TYPE Reply RECORD r ANY
TYPE Components LIST TEXT
TYPE Sample RECORD device ANY session ANY components Components
TYPE Steps LIST TEXT
TYPE Rows LIST ANY
TYPE MaybeRows OPTIONAL Rows
TYPE CheckedRows RECORD ok BOOLEAN value MaybeRows
TYPE MaybeLevel OPTIONAL INTEGER
TYPE CheckedLevel RECORD ok BOOLEAN value MaybeLevel
REG packet Packet
REG call Call
REG response Reply
REG sample Sample
REG binding Binding
REG target Address
REG steps Steps
REG components Components
REG checked CheckedRows
REG maybe_rows MaybeRows
REG rows Rows
REG checked_level CheckedLevel
REG maybe_level MaybeLevel
REG level INTEGER
REG kind TEXT
REG expect_kind TEXT
REG operation TEXT
REG request TEXT
REG field TEXT
REG component TEXT
REG block_component TEXT
REG expected TEXT
REG match BOOLEAN
REG args ANY
REG detail ANY
REG state ANY
REG value ANY
REG device ANY
REG session ANY
REG code ANY
REG guard_ok BOOLEAN
REG status TEXT
REG checkpoint TEXT
REG reply ANY
REG zero INTEGER
REG one INTEGER
REG version INTEGER
REG step INTEGER
REG total INTEGER
REG size INTEGER
REG row INTEGER
REG maximum_level INTEGER
CONST zero 0
SIO.WRITE p0 zero
CONST one 1
CONST version 1
CONST step 0
CONST total 8
CONST steps ["item_gate","fluid_gate","arm","deployer","items","fluid","item_gate","fluid_gate"]
CONST checkpoint "stop_feed"
CONST guard_ok false
CONST status "settling"
CONST reply null
FIELD binding layout item_gate
CONST block_component "create_biotech:block"
MOV component block_component
CONST maximum_level 15
SLEEP settle_ticks

check:
LIST.GET checkpoint steps step
CONST kind "component_settings_command"
CONST expect_kind "component_settings_reply"
CONST operation "arm_status"
CONST expected "arm"
VALUE.EQUALS match checkpoint expected
JFALSE match choose_deployer
FIELD target layout arm
JMP settings
choose_deployer:
CONST operation "deployer_status"
CONST expected "deployer"
VALUE.EQUALS match checkpoint expected
JFALSE match choose_reader
FIELD target layout deployer
settings:
CONST args {}
JMP send

choose_reader:
CONST kind "reader_command"
CONST expect_kind "reader_reply"
CONST operation "status"
MOV component block_component
CONST expected "item_gate"
VALUE.EQUALS match checkpoint expected
JFALSE match choose_valve
FIELD binding layout item_gate
JMP sensor
choose_valve:
CONST expected "fluid_gate"
VALUE.EQUALS match checkpoint expected
JFALSE match choose_items
FIELD binding layout fluid_gate
JMP sensor
choose_items:
CONST expected "items"
VALUE.EQUALS match checkpoint expected
JFALSE match choose_fluid
FIELD binding layout items
CONST component "create_biotech:redstone"
JMP sensor
choose_fluid:
FIELD binding layout fluid
CONST component "create_biotech:fluid"
sensor:
FIELD target binding reader
CONST args {}

send:
CORRELATION request
RECORD.MAKE packet kind kind version version target target request request operation operation arguments args
RECORD.MAKE call p packet e expect_kind
XBUS.WRITE x0 call
XBUS.READ response x0
FIELD reply response r
FIELD code reply code
CONST expected "ok"
CONST status "device_unavailable"
VALUE.EQUALS match code expected
JFALSE match done

CONST expected "status"
VALUE.EQUALS match operation expected
JFALSE match inspect
FIELD state reply state
FIELD device state device
FIELD session state session
CONST expected "items"
VALUE.EQUALS match checkpoint expected
JFALSE match one_component
LIST.MAKE components block_component component
JMP sample_args
one_component:
LIST.MAKE components component
sample_args:
RECORD.MAKE sample device device session session components components
MOV args sample
CONST operation "sample"
JMP send

inspect:
CONST expected "arm_status"
VALUE.EQUALS match operation expected
JFALSE match try_deployer
FIELD state reply state
CONST status "arm_not_idle"
CONST field "held"
HAS.FIELD match state field
JFALSE match done
FIELD detail state held
IS_NULL match detail
JFALSE match done
FIELD detail state phase
CONST expected "search_inputs"
VALUE.EQUALS match detail expected
JFALSE match done
FIELD detail state transfer
CONST expected "idle"
VALUE.EQUALS match detail expected
JFALSE match arm_previous
JMP advance
arm_previous:
CONST expected "transfer_completed"
VALUE.EQUALS match detail expected
JFALSE match done
JMP advance

try_deployer:
CONST expected "deployer_status"
VALUE.EQUALS match operation expected
JFALSE match inspect_sample
FIELD state reply state
CONST status "deployer_not_idle"
FIELD detail state locked
CONST value true
VALUE.EQUALS match detail value
JFALSE match done
FIELD detail state phase
CONST expected "waiting"
VALUE.EQUALS match detail expected
JFALSE match done
FIELD detail state process
CONST expected "idle"
VALUE.EQUALS match detail expected
JFALSE match deployer_previous
JMP advance
deployer_previous:
CONST expected "processed"
VALUE.EQUALS match detail expected
JFALSE match done
JMP advance

inspect_sample:
FIELD state reply value
CONST status "sample_unavailable"
CONST field "components"
HAS.FIELD match state field
JFALSE match done
FIELD detail state target
FIELD value binding block
CONST status "wrong_observation_target"
VALUE.EQUALS match detail value
JFALSE match done
FIELD state state components
CONST status "sample_unavailable"
HAS.FIELD match state component
JFALSE match done
VALUE.EQUALS match component block_component
JFALSE match material

FIELD state state create_biotech:block
CONST field "state"
HAS.FIELD match state field
JFALSE match done
FIELD detail state id
FIELD state state state
CONST status "wrong_gate"
CONST expected "item_gate"
VALUE.EQUALS match checkpoint expected
JFALSE match fluid_gate
CONST expected "create:brass_funnel"
VALUE.EQUALS match detail expected
JFALSE match done
FIELD value state powered
CONST expected "true"
JMP gate_state
fluid_gate:
CONST expected "create:fluid_valve"
VALUE.EQUALS match detail expected
JFALSE match done
FIELD value state enabled
CONST expected "false"
gate_state:
CONST status "gate_not_closed"
VALUE.EQUALS match value expected
JFALSE match done
JMP advance

material:
CONST expected "items"
VALUE.EQUALS match checkpoint expected
JFALSE match fluid
; A compact actual occupancy check for the vanilla barrel or a directly fed
; Create depot (held stack plus workpiece output, not material still on a belt).
HAS.FIELD match state block_component
JFALSE match done
FIELD detail state create_biotech:block
CONST field "id"
HAS.FIELD match detail field
JFALSE match done
FIELD detail detail id
CONST status "wrong_item_buffer"
CONST expected "minecraft:barrel"
VALUE.EQUALS match detail expected
JFALSE match depot_check
JMP buffer_checked
depot_check:
CONST expected "create:depot"
VALUE.EQUALS match detail expected
JFALSE match done
buffer_checked:
CONST status "sample_unavailable"
FIELD detail state create_biotech:redstone
IS_ERROR match detail
JFALSE match comparator_not_error
JMP done
comparator_not_error:
IS_NULL match detail
JFALSE match comparator_present
JMP done
comparator_present:
CONST field "comparator"
HAS.FIELD match detail field
JFALSE match done
FIELD value detail comparator
VALUE.CHECK checked_level value
FIELD match checked_level ok
JFALSE match done
FIELD maybe_level checked_level value
REQUIRE_VALUE level maybe_level
LESS_INTEGER match level zero
JFALSE match check_comparator_maximum
JMP done
check_comparator_maximum:
LESS_INTEGER match maximum_level level
JFALSE match comparator_valid
JMP done
comparator_valid:
CONST status "material_remaining"
EQUAL_INTEGER match level zero
JFALSE match done
JMP advance
fluid:
FIELD value state create_biotech:fluid
VALUE.CHECK checked value
FIELD match checked ok
JFALSE match done
FIELD maybe_rows checked value
REQUIRE_VALUE rows maybe_rows
LIST.LENGTH size rows
LESS_INTEGER match zero size
JFALSE match done
MOV row zero
next_row:
LIST.GET state rows row
CONST field "amount"
HAS.FIELD match state field
JFALSE match done
FIELD detail state amount
CONST status "material_remaining"
VALUE.EQUALS match detail zero
JFALSE match done
ADD_INTEGER row row one
LESS_INTEGER match row size
JFALSE match advance
CONST status "sample_unavailable"
JMP next_row

advance:
ADD_INTEGER step step one
LESS_INTEGER match step total
JFALSE match drained
JMP check
drained:
CONST checkpoint "finished"
CONST status "observed_drained"
CONST guard_ok true
done:
