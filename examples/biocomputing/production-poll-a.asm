# Complete editable procedures from production_poll_a.bcp
# request is a per-transport correlation; job is the stable production order identity.
# Procedure inputs/outputs and invocation wiring remain in the accompanying .bcp.

# Procedure request_definition
TYPE target_type RECORD x INTEGER y INTEGER z INTEGER
TYPE packet_type RECORD kind TEXT version INTEGER target target_type request TEXT operation TEXT arguments ANY
TYPE outcome_type RECORD ok BOOLEAN value ANY
TYPE order_type RECORD order TEXT
REG packet packet_type
REG outcome outcome_type
REG kind TEXT
REG version INTEGER
REG request TEXT
REG operation TEXT
REG arguments order_type
REG reply ANY
CONST kind "production_command"
CONST version 1
CONST operation "order"
CORRELATION request
RECORD.MAKE arguments order job
RECORD.MAKE packet kind kind version version target target request request operation operation arguments arguments
XBUS.WRITE x0 packet
XBUS.READ outcome x0
FIELD reply outcome value
