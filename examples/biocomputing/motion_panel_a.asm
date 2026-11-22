; Winch panel, one choice per explicit run. x0 uses the public serial_exchange
; relay. p0 is an explicitly wired Simple I/O output, not a serial frame.
; Read-only polls are bounded. Never replay publish, take_event, set or start.
TYPE Address RECORD x INTEGER y INTEGER z INTEGER
TYPE Packet RECORD kind TEXT version INTEGER target Address request TEXT operation TEXT arguments ANY
TYPE Call RECORD p Packet e TEXT
TYPE Reply RECORD r ANY
TYPE Publication RECORD state ANY layout ANY roots ANY
TYPE Claim RECORD state ANY sequence ANY
TYPE ClockSettings RECORD token ANY field TEXT value INTEGER
TYPE MotionStart RECORD token ANY mode TEXT amount INTEGER speed INTEGER
REG publication Publication
REG claim Claim
REG clock_settings ClockSettings
REG command MotionStart
REG packet Packet
REG call Call
REG response Reply
REG target Address
REG kind TEXT
REG reply_kind TEXT
REG op TEXT
REG expect TEXT
REG request TEXT
REG args ANY
REG state ANY
REG code ANY
REG revision ANY
REG seq ANY
REG event ANY
REG values ANY
REG candidate ANY
REG motion_token ANY
REG match BOOLEAN
REG phase INTEGER
REG compare INTEGER
REG remaining INTEGER
REG wait_ticks INTEGER
REG zero INTEGER
REG one INTEGER
REG three INTEGER
REG four INTEGER
REG five INTEGER
REG version INTEGER
REG level INTEGER
REG field TEXT
REG mode TEXT
REG status TEXT
REG distance INTEGER
REG interval INTEGER
REG interval_applied BOOLEAN
REG action ANY
REG arrival TEXT
REG reply ANY
CONST zero 0
CONST one 1
CONST three 3
CONST four 4
CONST five 5
CONST version 1
CONST args {}
CONST reply null
CONST distance 0
CONST interval 0
CONST interval_applied false
CONST action null
CONST arrival "not_measured"
CONST revision null
CONST motion_token null
CONST remaining 0
CONST wait_ticks 0
CONST level 0
SIO.WRITE p0 zero
MOV target terminal_pos
CONST kind "terminal_ui_command"
CONST reply_kind "terminal_ui_reply"
CONST op "status"
CONST expect "ok"
CONST status "terminal_unavailable"
CONST phase 0

send:
CORRELATION request
RECORD.MAKE packet kind kind version version target target request request operation op arguments args
RECORD.MAKE call p packet e reply_kind
XBUS.WRITE x0 call
XBUS.READ response x0
FIELD reply response r
FIELD code reply code
VALUE.EQUALS match code expect
JFALSE match done
; One transport subroutine in the source, with a visible phase-specific continuation.
CONST compare 0
LESS_INTEGER match compare phase
JFALSE match terminal_found
CONST compare 1
LESS_INTEGER match compare phase
JFALSE match panel_published
CONST compare 2
LESS_INTEGER match compare phase
JFALSE match inspect_queue
CONST compare 3
LESS_INTEGER match compare phase
JFALSE match inspect_choice
CONST compare 4
LESS_INTEGER match compare phase
JFALSE match inspect_drive
CONST compare 5
LESS_INTEGER match compare phase
JFALSE match set_interval
CONST compare 6
LESS_INTEGER match compare phase
JFALSE match start_motion
CONST compare 7
LESS_INTEGER match compare phase
JFALSE match accepted_motion
JMP inspect_motion

terminal_found:
FIELD state reply state
RECORD.MAKE publication state state layout panel roots args
MOV args publication
CONST op "publish"
CONST expect "published"
CONST status "panel_not_confirmed"
CONST phase 1
JMP send

panel_published:
FIELD state reply state
FIELD revision state revision
CONST remaining 20
CONST wait_ticks 40
read_queue:
CONST op "status"
CONST args {}
CONST expect "ok"
CONST status "terminal_unavailable"
CONST phase 2
JMP send
inspect_queue:
FIELD state reply state
FIELD candidate state revision
VALUE.EQUALS match revision candidate
CONST status "panel_changed"
JFALSE match done
FIELD candidate state event_count
VALUE.EQUALS match candidate zero
JFALSE match take_choice
CONST status "no_choice"
SUBTRACT_INTEGER remaining remaining one
LESS_INTEGER match zero remaining
JFALSE match done
SLEEP wait_ticks
JMP read_queue

take_choice:
FIELD seq state next_event
RECORD.MAKE claim state state sequence seq
MOV args claim
CONST op "take_event"
CONST expect "event"
CONST status "choice_not_confirmed"
CONST phase 3
JMP send
inspect_choice:
FIELD event reply event
FIELD candidate event layout_revision
VALUE.EQUALS match revision candidate
CONST status "stale_choice"
JFALSE match done
CONST status "invalid_choice"
FIELD candidate event kind
CONST expect "submit"
VALUE.EQUALS match candidate expect
JFALSE match done
FIELD candidate event name
CONST expect "lower"
VALUE.EQUALS match candidate expect
JFALSE match done
FIELD values event values
CONST field "distance"
HAS.FIELD match values field
JFALSE match done
FIELD candidate values distance
CONST expect "1"
VALUE.EQUALS match candidate expect
JFALSE match long_choice
MOV distance one
JMP choice_ready
long_choice:
CONST expect "3"
VALUE.EQUALS match candidate expect
JFALSE match done
MOV distance three
choice_ready:
MULTIPLY_INTEGER interval distance four
ADD_INTEGER interval interval four
MULTIPLY_INTEGER level distance five
MOV target gear_pos
CONST kind "component_settings_command"
CONST reply_kind "component_settings_reply"
CONST op "motion_status"
CONST args {}
CONST expect "ok"
CONST status "motion_unavailable"
CONST phase 4
JMP send

inspect_drive:
FIELD state reply state
FIELD code state availability
VALUE.EQUALS match code expect
JFALSE match no_motion
FIELD motion_token state token
MOV target timer_pos
CONST op "status"
CONST args {"field":"interval"}
CONST status "clock_unavailable"
CONST phase 5
JMP send
no_motion:
CONST expect "no_drive"
VALUE.EQUALS match code expect
JFALSE match done
MOV status expect
JMP done

set_interval:
FIELD state reply state
FIELD candidate state token
CONST field "interval"
RECORD.MAKE clock_settings token candidate field field value interval
MOV args clock_settings
CONST op "set"
CONST expect "applied"
CONST status "clock_not_confirmed"
CONST phase 6
JMP send

start_motion:
CONST interval_applied true
MOV target gear_pos
CONST mode "distance"
RECORD.MAKE command token motion_token mode mode amount distance speed one
MOV args command
CONST op "motion_start"
CONST expect "accepted"
CONST status "start_not_confirmed"
CONST phase 7
JMP send

accepted_motion:
FIELD state reply state
FIELD action state action
SIO.WRITE p0 level
CONST remaining 16
CONST wait_ticks 20
read_motion:
CONST op "motion_status"
CONST args {}
CONST expect "ok"
CONST status "motion_query_unavailable"
CONST phase 8
SLEEP wait_ticks
JMP send
inspect_motion:
FIELD state reply state
FIELD candidate state action
VALUE.EQUALS match action candidate
CONST status "action_changed"
JFALSE match done
FIELD candidate state phase
CONST expect "running"
VALUE.EQUALS match candidate expect
JFALSE match completed_motion
SUBTRACT_INTEGER remaining remaining one
LESS_INTEGER match zero remaining
JFALSE match motion_timeout
JMP read_motion
motion_timeout:
CONST status "motion_observation_timeout"
JMP done
completed_motion:
CONST status "motion_state_unknown"
CONST expect "sequence_completed"
VALUE.EQUALS match candidate expect
JFALSE match interrupted_motion
MOV status expect
JMP done
interrupted_motion:
CONST expect "interrupted"
VALUE.EQUALS match candidate expect
JFALSE match done
MOV status expect
done:
SIO.WRITE p0 zero
