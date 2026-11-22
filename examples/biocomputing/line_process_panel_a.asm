; Ask the authorized physical terminal for one new, named choice.
; No world action, not even stop-feed, is issued before the caller has a choice.
TYPE Address RECORD x INTEGER y INTEGER z INTEGER
TYPE Point RECORD index INTEGER offset Address type TEXT
TYPE Route RECORD input Point output Point
TYPE Rule RECORD domain TEXT mode TEXT matchers ANY
TYPE Filter RECORD target Address rule Rule
TYPE Profile RECORD item_filter Filter fluid_filter Filter deployer_mode TEXT arm_route Route
TYPE Packet RECORD kind TEXT version INTEGER target Address request TEXT operation TEXT arguments ANY
TYPE Call RECORD p Packet e TEXT
TYPE Reply RECORD r ANY
TYPE Publication RECORD state ANY layout ANY roots ANY
TYPE Claim RECORD state ANY sequence ANY
REG packet Packet
REG call Call
REG response Reply
REG publication Publication
REG claim Claim
REG profile Profile
REG kind TEXT
REG expect_kind TEXT
REG operation TEXT
REG expect TEXT
REG request TEXT
REG version INTEGER
REG phase INTEGER
REG marker INTEGER
REG zero INTEGER
REG one INTEGER
REG remaining INTEGER
REG wait_ticks INTEGER
REG match BOOLEAN
REG args ANY
REG state ANY
REG candidate ANY
REG device ANY
REG session ANY
REG revision ANY
REG sequence ANY
REG event ANY
REG choice_ready BOOLEAN
REG choice_status TEXT
REG chosen_process TEXT
REG choice_reply ANY
REG item_filter Filter
REG fluid_filter Filter
REG deployer_mode TEXT
REG arm_route Route

CONST choice_ready false
CONST chosen_process ""
CONST choice_reply null
; Typed outputs have safe defaults, but the caller must gate on choice_ready.
MOV profile process_one
CONST zero 0
CONST one 1
CONST version 1
CONST phase 0
CONST revision null
CONST session null
CONST device null
CONST sequence null
CONST remaining 20
CONST wait_ticks 40
CONST args {}
CONST kind "terminal_ui_command"
CONST expect_kind "terminal_ui_reply"
CONST operation "status"
CONST expect "ok"
CONST choice_status "terminal_unavailable"

send:
CORRELATION request
RECORD.MAKE packet kind kind version version target terminal_pos request request operation operation arguments args
RECORD.MAKE call p packet e expect_kind
XBUS.WRITE x0 call
XBUS.READ response x0
FIELD choice_reply response r
FIELD candidate choice_reply code
VALUE.EQUALS match candidate expect
JFALSE match done
FIELD state choice_reply state

LESS_INTEGER match zero phase
JFALSE match initial_state
LESS_INTEGER match one phase
JFALSE match published
CONST marker 2
LESS_INTEGER match marker phase
JFALSE match inspect_queue
JMP inspect_event

initial_state:
RECORD.MAKE publication state state layout choice_panel roots args
MOV args publication
MOV phase one
CONST operation "publish"
CONST expect "published"
CONST choice_status "panel_not_confirmed"
JMP send

published:
FIELD revision state revision
FIELD session state session
FIELD device state device
CONST remaining 20
CONST wait_ticks 40

poll:
CONST phase 2
CONST operation "status"
CONST expect "ok"
CONST choice_status "terminal_unavailable"
CONST args {}
JMP send

inspect_queue:
CONST choice_status "panel_changed"
FIELD candidate state revision
VALUE.EQUALS match candidate revision
JFALSE match done
FIELD candidate state session
VALUE.EQUALS match candidate session
JFALSE match done
FIELD candidate state device
VALUE.EQUALS match candidate device
JFALSE match done
FIELD sequence state next_event
IS_NULL match sequence
JFALSE match take_event
CONST choice_status "no_choice"
SUBTRACT_INTEGER remaining remaining one
LESS_INTEGER match zero remaining
JFALSE match done
SLEEP wait_ticks
JMP poll

take_event:
RECORD.MAKE claim state state sequence sequence
MOV args claim
CONST phase 3
CONST operation "take_event"
CONST expect "event"
CONST choice_status "choice_not_confirmed"
JMP send

inspect_event:
FIELD event choice_reply event
CONST choice_status "stale_choice"
FIELD candidate event layout_revision
VALUE.EQUALS match candidate revision
JFALSE match done
FIELD candidate event sequence
VALUE.EQUALS match candidate sequence
JFALSE match done
CONST choice_status "invalid_choice"
FIELD candidate event kind
CONST expect "click"
VALUE.EQUALS match candidate expect
JFALSE match done
FIELD candidate event name
CONST expect "process_one"
VALUE.EQUALS match candidate expect
JFALSE match try_second
JMP chosen

try_second:
CONST expect "process_two"
VALUE.EQUALS match candidate expect
JFALSE match done
MOV profile process_two

chosen:
MOV chosen_process expect
CONST choice_ready true
CONST choice_status "chosen"

done:
FIELD item_filter profile item_filter
FIELD fluid_filter profile fluid_filter
FIELD deployer_mode profile deployer_mode
FIELD arm_route profile arm_route
