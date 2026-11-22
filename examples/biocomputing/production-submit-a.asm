# Complete editable procedures from production_submit_a.bcp
# request is a per-transport correlation; job is the stable production order identity.
# Procedure inputs/outputs and invocation wiring remain in the accompanying .bcp.

# Procedure request_definition
TYPE target_type RECORD x INTEGER y INTEGER z INTEGER
TYPE packet_type RECORD kind TEXT version INTEGER target target_type request TEXT operation TEXT arguments ANY
TYPE outcome_type RECORD ok BOOLEAN value ANY
TYPE items_type LIST ANY
TYPE data_type RECORD items items_type
TYPE submit_type RECORD device ANY session ANY order TEXT data data_type
REG packet packet_type
REG outcome outcome_type
REG kind TEXT
REG version INTEGER
REG request TEXT
REG operation TEXT
REG arguments ANY
REG data data_type
REG submission submit_type
REG reply ANY
REG code ANY
REG state ANY
REG device ANY
REG session ANY
REG expected TEXT
REG flag BOOLEAN
REG submitting BOOLEAN
CONST kind "production_command"
CONST version 1
CONST operation "status"
CONST arguments {}
CONST expected "ok"
CONST submitting false
CORRELATION request
send:
RECORD.MAKE packet kind kind version version target target request request operation operation arguments arguments
XBUS.WRITE x0 packet
XBUS.READ outcome x0
FIELD flag outcome ok
FIELD reply outcome value
JFALSE flag done
JFALSE submitting prepare
JMP done
prepare:
FIELD code reply code
VALUE.EQUALS flag code expected
JFALSE flag done
FIELD state reply state
FIELD device state device
FIELD session state session
RECORD.MAKE data items items
RECORD.MAKE submission device device session session order job data data
MOV arguments submission
CORRELATION request
CONST operation "submit"
CONST submitting true
JMP send
done:
