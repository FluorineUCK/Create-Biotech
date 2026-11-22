# Complete editable procedures from redstone_output_b.bcp
# request is a per-transport correlation; job is the stable production order identity.
# Procedure inputs/outputs and invocation wiring remain in the accompanying .bcp.

# Procedure implementation
TYPE target_type RECORD x INTEGER y INTEGER z INTEGER
TYPE packet_type RECORD kind TEXT version INTEGER target target_type request TEXT operation TEXT arguments ANY
TYPE outcome_type RECORD ok BOOLEAN value ANY
TYPE context_type RECORD p packet_type r ANY
TYPE match_type RECORD m BOOLEAN
TYPE timeout_type RECORD status TEXT line SERIAL_STATUS
REG packet packet_type
REG outcome outcome_type
REG sent SERIAL_SEND
REG received SERIAL_RECEIVE
REG serial SERIAL_STATUS
REG context context_type
REG match match_type
REG reply ANY
REG success BOOLEAN
REG flag BOOLEAN
REG empty TEXT
REG state ANY
REG remaining INTEGER
REG one INTEGER
REG reason TEXT
REG timed_out timeout_type
CONST empty "empty"
CONST remaining 48
CONST one 1
XBUS.READ packet x0
SERIAL.SEND s0 packet sent
MOV reply sent
FIELD success sent ok
JFALSE success done
JMP poll
receive_status:
MOV reply received
FIELD state received status
VALUE.EQUALS flag state empty
JFALSE flag settle
retry:
SUBTRACT_INTEGER remaining remaining one
LESS_INTEGER flag remaining one
JFALSE flag wait_poll
SERIAL.STATUS serial s0
CONST reason "reply_timeout"
RECORD.MAKE timed_out status reason line serial
MOV reply timed_out
JMP done
wait_poll:
SLEEP interval
poll:
SERIAL.RECEIVE received s0
FIELD success received ok
JFALSE success receive_status
FIELD reply received value
RECORD.MAKE context p packet r reply
XBUS.WRITE x1 context
XBUS.READ match x1
FIELD success match m
JFALSE success retry
settle:
SLEEP interval
done:
RECORD.MAKE outcome ok success value reply
XBUS.WRITE x0 outcome
