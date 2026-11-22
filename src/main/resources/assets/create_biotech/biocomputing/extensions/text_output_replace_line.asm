; Replace the whole page with exactly one line. Compare the status token at the adapter.
TYPE location RECORD x INTEGER y INTEGER z INTEGER
TYPE page LIST TEXT
TYPE payload RECORD device TEXT session TEXT revision INTEGER lines page
TYPE message RECORD kind TEXT version INTEGER target location request TEXT operation TEXT arguments payload
REG target location
REG packet message
REG arguments payload
REG kind TEXT
REG version INTEGER
REG operation TEXT
REG queued SERIAL_SEND
REG request TEXT
CONST kind "text_output_command"
CONST version 1
REG lines page
CONST operation "replace"
LIST.MAKE lines line
RECORD.MAKE arguments device device session session revision revision lines lines
CORRELATION request
RECORD.MAKE target x x y y z z
RECORD.MAKE packet kind kind version version target target request request operation operation arguments arguments
SERIAL.SEND s0 packet queued
RET
